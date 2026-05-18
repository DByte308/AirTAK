// AirTAK Android — data/mesh/MeshRepository.kt
// Planning: §11.6, §10.3, §10.4
//
// Bridge between the BLE transport and the domain layer:
//   - serializes outbound text / proto packets into MESH_TX
//   - decodes inbound MESH_RX envelopes and fans them out
//   - persists TEXT_MESSAGE_APP traffic to Room
//
// Phase 3 scope covers text messages only; later phases (4 — players, 5 —
// zones / game / admin) hook additional portnums into the same dispatcher.

package com.airtak.data.mesh

import com.airtak.data.ble.BleRepository
import com.airtak.data.ble.InboundChannel
import com.airtak.data.ble.LinkState
import com.airtak.data.config.ConfigRepository
import com.airtak.data.db.MessageDao
import com.airtak.data.db.MessageEntity
import com.airtak.data.status.DeviceStatusRepository
import com.airtak.proto.MarkerSnapshotRequest
import com.airtak.proto.ModerationPacket
import com.airtak.proto.ChannelConfig
import com.airtak.proto.ChannelRole
import com.airtak.proto.ChannelSlot
import com.airtak.proto.ConfigGroup
import com.airtak.proto.OverlayAlignment
import com.airtak.proto.PlayerStatePacket
import com.airtak.proto.PositionRequestPacket
import com.airtak.proto.SyncRequest
import com.airtak.proto.TacticalMarker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MeshRepository @Inject constructor(
    private val ble: BleRepository,
    private val messageDao: MessageDao,
    private val configRepo: ConfigRepository,
    private val deviceStatus: DeviceStatusRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Maps channel_hash byte (0–255) → slot index (0–7).
     * Recomputed whenever the device posts updated CHANNELS config.
     * Mirrors Crypto::channelHash() from the firmware (§8.6).
     */
    private val channelHashIndex = ConcurrentHashMap<Int, Int>()

    /** Compute the 1-byte channel hash for a slot, matching the firmware algorithm. */
    private fun computeChannelHash(slot: ChannelSlot): Int {
        val keyBytes = if (slot.psk.size() > 0) {
            slot.psk.toByteArray()
        } else {
            // Open channel: firmware uses slot name bytes as the key input.
            slot.name.toByteArray(Charsets.UTF_8)
        }
        return if (slot.index == 0) {
            // Slot 0: Meshtastic-compatible XOR fold of all 32 key bytes (zero-padded).
            val padded = ByteArray(32)
            keyBytes.copyInto(padded, 0, 0, minOf(keyBytes.size, 32))
            padded.fold(0) { acc, b -> acc xor (b.toInt() and 0xFF) } and 0xFF
        } else {
            // Slots 1–7: first byte of SHA-256(keyBytes).
            MessageDigest.getInstance("SHA-256").digest(keyBytes)[0].toInt() and 0xFF
        }
    }

    /** All decoded mesh packets, regardless of portnum. */
    private val _packets = MutableSharedFlow<MeshRxPacket>(extraBufferCapacity = 128)
    val packets: SharedFlow<MeshRxPacket> = _packets.asSharedFlow()

    /** requestId → DB message row id; populated on send, removed when ACK arrives. */
    private val pendingAcks = ConcurrentHashMap<Long, Long>()

    /** MRU cache of recently-handled sync_ids for message rebroadcast
     *  dedup. Same policy as TacticalMarkerRepository's cache. */
    private val handledSyncIds = object : LinkedHashMap<Long, Long>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Long>?): Boolean =
            size > 8 || (eldest != null && System.currentTimeMillis() > eldest.value)
    }

    init {
        // Keep channelHashIndex in sync with the device config.
        scope.launch {
            configRepo.groupBytes.collect { map ->
                val bytes = map[ConfigGroup.CHANNELS] ?: return@collect
                val config = runCatching { ChannelConfig.parseFrom(bytes) }.getOrNull() ?: return@collect
                val next = HashMap<Int, Int>()
                config.slotsList.forEach { slot ->
                    if (slot.role != ChannelRole.DISABLED) {
                        next[computeChannelHash(slot)] = slot.index
                    }
                }
                channelHashIndex.clear()
                channelHashIndex.putAll(next)
            }
        }
        scope.launch {
            ble.inbound
                .filter { it.channel == InboundChannel.MeshRx }
                .collect { frame ->
                    val pkt = MeshWire.decodeMeshRx(frame.payload) ?: return@collect
                    _packets.emit(pkt)
                    if (pkt.portnum == Portnum.TEXT_MESSAGE_APP && pkt.payload != null) {
                        persistIncomingText(pkt)
                    }
                    if (pkt.portnum == Portnum.AIRTAK_ACK) {
                        val reqId = pkt.requestId ?: return@collect
                        val msgId = pendingAcks.remove(reqId) ?: return@collect
                        messageDao.markDelivered(msgId)
                    }
                }
        }
        // Send sync request on BLE connect so late-joiners receive the full state.
        scope.launch {
            ble.linkState
                .map { it is LinkState.Connected }
                .distinctUntilChanged()
                .filter { it }
                .collect { triggerSync() }
        }
        // Handle incoming sync requests: re-broadcast recent text messages.
        scope.launch {
            _packets
                .filter { it.portnum == Portnum.AIRTAK_SYNC_REQUEST }
                .collect { handleSyncRequest(it) }
        }
    }

    /**
     * Send a UTF-8 text message on the primary channel. The byte-count cap is
     * enforced by the caller (Messages screen) and is also rechecked here so
     * callers cannot accidentally exceed the 237-byte LoRa payload (§11.6).
     */
    /** Send a direct (unicast) text message to a specific node. The message
     *  propagates through the mesh; AIRTAK_ACK fires when the target receives it. */
    fun sendDm(peerNodeId: Long, peerCallsign: String, text: String): SendResult {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_TEXT_BYTES) return SendResult.TooLong(bytes.size)
        val requestId = (System.nanoTime() and 0x7FFF_FFFFL) or 1L
        val data = MeshWire.encodeData(
            portnum = Portnum.TEXT_MESSAGE_APP,
            payload = bytes,
            wantResponse = true,
            destNodeId = peerNodeId,
            requestId = requestId,
        )
        ble.send(BleRepository.OutboundChannel.MeshTx, data)
        scope.launch {
            val msgId = messageDao.insert(
                MessageEntity(
                    channelIndex = DM_CHANNEL_INDEX,
                    sourceNodeId = 0,
                    sourceCallsign = "",
                    text = text,
                    timestampMs = System.currentTimeMillis(),
                    outgoing = true,
                    delivered = false,
                    peerNodeId = peerNodeId,
                ),
            )
            pendingAcks[requestId] = msgId
        }
        return SendResult.Ok()
    }

    fun observeDm(peerNodeId: Long): Flow<List<MessageEntity>> = messageDao.observeDm(peerNodeId)
    fun observeDmPeers(): Flow<List<MessageEntity>> = messageDao.observeDmPeers()

    fun sendText(channel: Int, text: String): SendResult {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_TEXT_BYTES) return SendResult.TooLong(bytes.size)
        // Use a non-zero 32-bit request ID so the firmware can ACK this packet (§8.9).
        val requestId = (System.nanoTime() and 0x7FFF_FFFFL) or 1L
        val dataProto = MeshWire.encodeData(
            portnum = Portnum.TEXT_MESSAGE_APP,
            payload = bytes,
            wantResponse = true,
            requestId = requestId,
        )
        // Prepend a 1-byte slot-override prefix so the firmware routes this
        // packet through the correct channel slot (§10.3, slot-override protocol).
        // Byte 0x00–0x07 are never valid proto3 field tags, so the firmware can
        // distinguish this prefix from a legacy raw Data proto.
        val slotByte = (channel and 0x07).toByte()
        val data = byteArrayOf(slotByte) + dataProto
        ble.send(BleRepository.OutboundChannel.MeshTx, data)
        scope.launch {
            val msgId = messageDao.insert(
                MessageEntity(
                    channelIndex = channel,
                    sourceNodeId = 0,
                    sourceCallsign = "",
                    text = text,
                    timestampMs = System.currentTimeMillis(),
                    outgoing = true,
                    delivered = false,
                ),
            )
            pendingAcks[requestId] = msgId
            messageDao.pruneOldest(channel, MESSAGE_WINDOW)
        }
        return SendResult.Ok()
    }

    fun sendPlayerState(packet: PlayerStatePacket): SendResult {
        val requestId = if (packet.isStateTransition) {
            (System.nanoTime() and 0x7FFF_FFFFL) or 1L
        } else null
        val data = MeshWire.encodeData(
            portnum = Portnum.AIRTAK_PLAYER,
            payload = packet.toByteArray(),
            wantResponse = packet.isStateTransition,
            requestId = requestId,
        )
        ble.send(BleRepository.OutboundChannel.MeshTx, data)
        return SendResult.Ok()
    }

    fun sendModerationPacket(packet: ModerationPacket): SendResult {
        val requestId = (System.nanoTime() and 0x7FFF_FFFFL) or 1L
        val data = MeshWire.encodeData(
            portnum = Portnum.AIRTAK_MODERATION,
            payload = packet.toByteArray(),
            wantResponse = true,
            requestId = requestId,
        )
        ble.send(BleRepository.OutboundChannel.MeshTx, data)
        return SendResult.Ok()
    }

    /**
     * Broadcast a tactical marker (create / update / delete tombstone).
     * ACKNOWLEDGED delivery (§5.3, §8.9). [channelSlot] selects which channel
     * slot the firmware should encrypt with — typically slot 1 (AirTAK-wide
     * tactical) for SCOPE_ALL markers, or the unit-bound slot for scoped ones.
     * Pass `null` to fall back to the primary slot.
     */
    fun sendTacticalMarker(marker: TacticalMarker, channelSlot: Int? = null): SendResult {
        val payload = marker.toByteArray()
        if (payload.size > MAX_MARKER_BYTES) return SendResult.TooLong(payload.size)
        val requestId = (System.nanoTime() and 0x7FFF_FFFFL) or 1L
        val dataProto = MeshWire.encodeData(
            portnum = Portnum.AIRTAK_TACTICAL,
            payload = payload,
            wantResponse = true,
            requestId = requestId,
        )
        val data = if (channelSlot != null && channelSlot in 0..7) {
            byteArrayOf((channelSlot and 0x07).toByte()) + dataProto
        } else {
            dataProto
        }
        ble.send(BleRepository.OutboundChannel.MeshTx, data)
        return SendResult.Ok(requestId)
    }

    fun requestPosition(targetNodeId: Long, requesterNodeId: Long): SendResult {
        val request = PositionRequestPacket.newBuilder()
            .setTargetNodeId((targetNodeId and 0xFFFF_FFFFL).toInt())
            .setRequesterNodeId((requesterNodeId and 0xFFFF_FFFFL).toInt())
            .build()
        val data = MeshWire.encodeData(Portnum.AIRTAK_POSREQ, request.toByteArray())
        ble.send(BleRepository.OutboundChannel.MeshTx, data)
        return SendResult.Ok()
    }

    fun observeMessages(channel: Int): Flow<List<MessageEntity>> = messageDao.observe(channel)

    sealed interface SendResult {
        data class Ok(val requestId: Long = 0) : SendResult
        data class TooLong(val attemptedBytes: Int) : SendResult
    }

    private suspend fun persistIncomingText(pkt: MeshRxPacket) {
        val text = pkt.payload?.toString(Charsets.UTF_8) ?: return
        // Unicast (non-broadcast dest) → DM store; broadcast → channel store.
        val isDm = pkt.destNodeId != 0L && pkt.destNodeId != 0xFFFF_FFFFL
        val peerNodeId = if (isDm) pkt.srcNodeId else 0L
        // Resolve the slot index by matching the firmware-computed channel_hash byte.
        // Falls back to slot 0 (primary) if the hash is not yet in the map
        // (e.g. config not yet read from device).
        val channel = if (isDm) DM_CHANNEL_INDEX else (channelHashIndex[pkt.channelHash] ?: 0)
        messageDao.insert(
            MessageEntity(
                channelIndex = channel,
                sourceNodeId = pkt.srcNodeId,
                sourceCallsign = "",
                text = text,
                timestampMs = System.currentTimeMillis(),
                outgoing = false,
                peerNodeId = peerNodeId,
            ),
        )
        if (!isDm) messageDao.pruneOldest(channel, MESSAGE_WINDOW)
    }

    private fun ownNodeId(): Long =
        deviceStatus.status.value?.nodeId?.toLong()?.and(0xFFFF_FFFFL) ?: 0L

    fun triggerSync() {
        val nodeId = ownNodeId()
        if (nodeId == 0L) return
        val syncId = (System.nanoTime() and 0x7FFF_FFFFL) or 1L
        val request = SyncRequest.newBuilder()
            .setRequesterNodeId(nodeId.toInt())
            .setRequestedTypes(SYNC_TYPE_MARKERS or SYNC_TYPE_MESSAGES)
            .setSyncId(syncId.toInt())
            .build()
        val data = MeshWire.encodeData(
            portnum = Portnum.AIRTAK_SYNC_REQUEST,
            payload = request.toByteArray(),
        )
        ble.send(BleRepository.OutboundChannel.MeshTx, data)
    }

    private suspend fun handleSyncRequest(packet: MeshRxPacket) {
        val payload = packet.payload ?: return
        val request = runCatching { SyncRequest.parseFrom(payload) }.getOrNull() ?: return
        val syncId = request.syncId.toLong() and 0xFFFF_FFFFL
        if (syncId != 0L && isSyncIdHandled(syncId)) return
        val requesterId = request.requesterNodeId.toLong() and 0xFFFF_FFFFL
        if (requesterId == ownNodeId()) return

        val types = request.requestedTypes
        if (types and SYNC_TYPE_MESSAGES != 0) {
            markSyncIdHandled(syncId)
            rebroadcastRecentMessages()
        }
    }

    private fun isSyncIdHandled(syncId: Long): Boolean =
        synchronized(handledSyncIds) { handledSyncIds.containsKey(syncId) }

    private fun markSyncIdHandled(syncId: Long) {
        if (syncId == 0L) return
        synchronized(handledSyncIds) {
            handledSyncIds[syncId] = System.currentTimeMillis() + SYNC_ID_TTL_MS
        }
    }

    private suspend fun rebroadcastRecentMessages() {
        val messages = messageDao.recentMessages(SYNC_MESSAGE_COUNT)
        for (msg in messages) {
            if (!msg.outgoing) continue
            val text = msg.text
            if (text.length > MAX_TEXT_BYTES) continue
            sendText(channel = msg.channelIndex, text = text)
            delay(SYNC_SPACING_MS)
        }
    }

    fun sendOverlayAlignment(alignment: OverlayAlignment): SendResult {
        val requestId = (System.nanoTime() and 0x7FFF_FFFFL) or 1L
        val data = MeshWire.encodeData(
            portnum = Portnum.AIRTAK_OVERLAY_ALIGNMENT,
            payload = alignment.toByteArray(),
            wantResponse = true,
            requestId = requestId,
        )
        ble.send(BleRepository.OutboundChannel.MeshTx, data)
        return SendResult.Ok(requestId)
    }

    fun requestMarkerSnapshot(targetNodeId: Long): SendResult {
        val ownId = deviceStatus.status.value?.nodeId?.toLong()?.and(0xFFFF_FFFFL) ?: 0L
        if (ownId == 0L || targetNodeId == 0L) return SendResult.Ok(0)
        val syncId = (System.nanoTime() and 0x7FFF_FFFFL) or 1L
        val request = MarkerSnapshotRequest.newBuilder()
            .setTargetNodeId((targetNodeId and 0xFFFF_FFFFL).toInt())
            .setRequesterNodeId(ownId.toInt())
            .setSyncId(syncId.toInt())
            .build()
        val requestId = (System.nanoTime() and 0x7FFF_FFFFL) or 1L
        val data = MeshWire.encodeData(
            portnum = Portnum.AIRTAK_MARKER_SNAPSHOT_REQUEST,
            payload = request.toByteArray(),
            wantResponse = true,
            destNodeId = targetNodeId,
            requestId = requestId,
        )
        ble.send(BleRepository.OutboundChannel.MeshTx, data)
        return SendResult.Ok(requestId)
    }

    companion object {
        const val MAX_TEXT_BYTES = 237
        /** Conservative upper bound for a TacticalMarker proto body. The 237-byte
         *  LoRa payload (§11.6) minus the small Data envelope overhead. */
        const val MAX_MARKER_BYTES = 220
        const val MESSAGE_WINDOW = 1_000
        /** Synthetic channelIndex value used internally for DM rows; never shown as a tab index. */
        const val DM_CHANNEL_INDEX = -1
        private const val SYNC_SPACING_MS = 500L
        private const val SYNC_MESSAGE_COUNT = 50
        private const val SYNC_TYPE_MARKERS = 1
        private const val SYNC_TYPE_MESSAGES = 2
        private const val SYNC_ID_TTL_MS = 10_000L
    }
}
