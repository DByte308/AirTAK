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
import java.util.concurrent.ConcurrentLinkedDeque
import javax.inject.Inject
import javax.inject.Singleton
import com.airtak.config.constants.MESH_DM_CHANNEL_INDEX
import com.airtak.config.constants.MESH_INBOUND_BUFFER
import com.airtak.config.constants.MESH_MESH_MAX_MARKER_BYTES
import com.airtak.config.constants.MESH_MESH_MAX_TEXT_BYTES
import com.airtak.config.constants.MESH_MESSAGE_WINDOW
import com.airtak.config.constants.MESH_MESH_SYNC_ID_TTL_MS
import com.airtak.config.constants.MESH_MESH_SYNC_MESSAGE_COUNT
import com.airtak.config.constants.MESH_MESH_SYNC_SPACING_MS
import com.airtak.config.constants.MESH_MESH_SYNC_TYPE_MARKERS
import com.airtak.config.constants.MESH_MESH_SYNC_TYPE_MESSAGES
import com.airtak.config.constants.CHANNEL_SLOT_MASK
import com.airtak.config.constants.MESH_AES_KEY_BYTES
import com.airtak.config.constants.MESH_BROADCAST_ADDR
import com.airtak.config.constants.MESH_SYNC_ID_CACHE_SIZE
import com.airtak.config.constants.REQUEST_ID_MASK
import com.airtak.config.constants.UINT32_MASK
import com.airtak.config.constants.PORTNUM_AIRTAK_ACK
import com.airtak.config.constants.PORTNUM_AIRTAK_MARKER_SNAPSHOT_REQUEST
import com.airtak.config.constants.PORTNUM_AIRTAK_MODERATION
import com.airtak.config.constants.PORTNUM_AIRTAK_OVERLAY_ALIGNMENT
import com.airtak.config.constants.PORTNUM_AIRTAK_PLAYER
import com.airtak.config.constants.PORTNUM_AIRTAK_POSREQ
import com.airtak.config.constants.PORTNUM_AIRTAK_SYNC_REQUEST
import com.airtak.config.constants.PORTNUM_AIRTAK_TACTICAL
import com.airtak.config.constants.PORTNUM_ROUTING_APP
import com.airtak.config.constants.PORTNUM_TEXT_MESSAGE_APP

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
            val padded = ByteArray(MESH_AES_KEY_BYTES)
            keyBytes.copyInto(padded, 0, 0, minOf(keyBytes.size, MESH_AES_KEY_BYTES))
            padded.fold(0) { acc, b -> acc xor (b.toInt() and 0xFF) } and 0xFF
        } else {
            // Slots 1–7: first byte of SHA-256(keyBytes).
            MessageDigest.getInstance("SHA-256").digest(keyBytes)[0].toInt() and 0xFF
        }
    }

    /** All decoded mesh packets, regardless of portnum. */
    private val _packets = MutableSharedFlow<MeshRxPacket>(extraBufferCapacity = MESH_INBOUND_BUFFER)
    val packets: SharedFlow<MeshRxPacket> = _packets.asSharedFlow()

    /** requestId → DB message row id; populated on send, removed when ACK arrives. */
    private val pendingAcks = ConcurrentHashMap<Long, Long>()
    /**
     * FIFO-ordered journal of pending sent messages.  Used as a fallback when
     * the radio (e.g. Meshtastic) replaces Data.request_id with the OTA packet_id
     * before forwarding the ROUTING_APP ACK, so the request-id map lookup misses.
     * Entries are dequeued oldest-first when an unmatched ACK arrives.
     */
    private val pendingAckJournal = ConcurrentLinkedDeque<Long>()  // msgId

    /** MRU cache of recently-handled sync_ids for message rebroadcast
     *  dedup. Same policy as TacticalMarkerRepository's cache. */
    private val handledSyncIds = object : LinkedHashMap<Long, Long>(MESH_SYNC_ID_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Long>?): Boolean =
            size > MESH_SYNC_ID_CACHE_SIZE || (eldest != null && System.currentTimeMillis() > eldest.value)
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
                    if (pkt.portnum == PORTNUM_TEXT_MESSAGE_APP && pkt.payload != null) {
                        persistIncomingText(pkt)
                    }
                    if (pkt.portnum == PORTNUM_AIRTAK_ACK ||
                        pkt.portnum == PORTNUM_ROUTING_APP) {
                        val reqId = pkt.requestId ?: return@collect
                        if (pkt.portnum == PORTNUM_ROUTING_APP) {
                            val err = MeshWire.decodeRoutingErrorReason(pkt.payload)
                            if (err != null && err != 0L) {
                                return@collect
                            }
                        }
                        var msgId = pendingAcks.remove(reqId)
                        if (msgId != null) {
                            pendingAckJournal.remove(msgId)
                        } else {
                            msgId = pendingAckJournal.poll()
                        }
                        if (msgId != null) {
                            messageDao.markDelivered(msgId)
                        }
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
                .filter { it.portnum == PORTNUM_AIRTAK_SYNC_REQUEST }
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
        if (bytes.size > MESH_MAX_TEXT_BYTES) return SendResult.TooLong(bytes.size)
        val requestId = (System.nanoTime() and REQUEST_ID_MASK) or 1L
        val data = MeshWire.encodeData(
            portnum = PORTNUM_TEXT_MESSAGE_APP,
            payload = bytes,
            wantResponse = true,
            destNodeId = peerNodeId,
            requestId = requestId,
        )
        ble.send(BleRepository.OutboundChannel.MeshTx, data)
        scope.launch {
            val msgId = messageDao.insert(
                MessageEntity(
                    channelIndex = MESH_DM_CHANNEL_INDEX,
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
            pendingAckJournal.add(msgId)
        }
        return SendResult.Ok()
    }

    fun observeDm(peerNodeId: Long): Flow<List<MessageEntity>> = messageDao.observeDm(peerNodeId)
    fun observeDmPeers(): Flow<List<MessageEntity>> = messageDao.observeDmPeers()

    fun sendText(channel: Int, text: String, bestEffort: Boolean = false): SendResult {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MESH_MAX_TEXT_BYTES) return SendResult.TooLong(bytes.size)
        val wantResponse = !bestEffort
        val requestId = if (wantResponse) {
            (System.nanoTime() and REQUEST_ID_MASK) or 1L
        } else null
        val dataProto = MeshWire.encodeData(
            portnum = PORTNUM_TEXT_MESSAGE_APP,
            payload = bytes,
            wantResponse = wantResponse,
            requestId = requestId,
        )
        // Prepend a 1-byte slot-override prefix so the firmware routes this
        // packet through the correct channel slot (§10.3, slot-override protocol).
        // Byte 0x00–CHANNEL_SLOT_MASK are never valid proto3 field tags, so the firmware can
        // distinguish this prefix from a legacy raw Data proto.
        val slotByte = (channel and CHANNEL_SLOT_MASK).toByte()
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
                    delivered = bestEffort, // mark as delivered immediately for best-effort
                ),
            )
            if (wantResponse && requestId != null) {
                pendingAcks[requestId] = msgId
                pendingAckJournal.add(msgId)
            }
            messageDao.pruneOldest(channel, MESH_MESSAGE_WINDOW)
        }
        return SendResult.Ok()
    }

    fun sendPlayerState(packet: PlayerStatePacket): SendResult {
        val requestId = if (packet.isStateTransition) {
            (System.nanoTime() and REQUEST_ID_MASK) or 1L
        } else null
        val data = MeshWire.encodeData(
            portnum = PORTNUM_AIRTAK_PLAYER,
            payload = packet.toByteArray(),
            wantResponse = packet.isStateTransition,
            requestId = requestId,
        )
        ble.send(BleRepository.OutboundChannel.MeshTx, data)
        return SendResult.Ok()
    }

    fun sendModerationPacket(packet: ModerationPacket): SendResult {
        val requestId = (System.nanoTime() and REQUEST_ID_MASK) or 1L
        val data = MeshWire.encodeData(
            portnum = PORTNUM_AIRTAK_MODERATION,
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
        if (payload.size > MESH_MAX_MARKER_BYTES) return SendResult.TooLong(payload.size)
        val requestId = (System.nanoTime() and REQUEST_ID_MASK) or 1L
        val dataProto = MeshWire.encodeData(
            portnum = PORTNUM_AIRTAK_TACTICAL,
            payload = payload,
            wantResponse = true,
            requestId = requestId,
        )
        val data = if (channelSlot != null && channelSlot in 0..CHANNEL_SLOT_MASK) {
            byteArrayOf((channelSlot and CHANNEL_SLOT_MASK).toByte()) + dataProto
        } else {
            dataProto
        }
        ble.send(BleRepository.OutboundChannel.MeshTx, data)
        return SendResult.Ok(requestId)
    }

    fun requestPosition(targetNodeId: Long, requesterNodeId: Long): SendResult {
        val request = PositionRequestPacket.newBuilder()
            .setTargetNodeId((targetNodeId and UINT32_MASK).toInt())
            .setRequesterNodeId((requesterNodeId and UINT32_MASK).toInt())
            .build()
        val requestId = (System.nanoTime() and REQUEST_ID_MASK) or 1L
        val data = MeshWire.encodeData(
            portnum = PORTNUM_AIRTAK_POSREQ,
            payload = request.toByteArray(),
            wantResponse = true,
            destNodeId = targetNodeId,
            requestId = requestId,
        )
        ble.send(BleRepository.OutboundChannel.MeshTx, data)
        return SendResult.Ok(requestId)
    }

    fun observeMessages(channel: Int): Flow<List<MessageEntity>> = messageDao.observe(channel)

    sealed interface SendResult {
        data class Ok(val requestId: Long = 0) : SendResult
        data class TooLong(val attemptedBytes: Int) : SendResult
    }

    private suspend fun persistIncomingText(pkt: MeshRxPacket) {
        val text = pkt.payload?.toString(Charsets.UTF_8) ?: return
        // Unicast (non-broadcast dest) → DM store; broadcast → channel store.
        val isDm = pkt.destNodeId != 0L && pkt.destNodeId != MESH_BROADCAST_ADDR
        val peerNodeId = if (isDm) pkt.srcNodeId else 0L
        // Resolve the slot index by matching the firmware-computed channel_hash byte.
        // Falls back to slot 0 (primary) if the hash is not yet in the map
        // (e.g. config not yet read from device).
        val channel = if (isDm) MESH_DM_CHANNEL_INDEX else (channelHashIndex[pkt.channelHash] ?: 0)
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
        if (!isDm) messageDao.pruneOldest(channel, MESH_MESSAGE_WINDOW)
    }

    private fun ownNodeId(): Long =
        deviceStatus.status.value?.nodeId?.toLong()?.and(UINT32_MASK) ?: 0L

    fun triggerSync() {
        val nodeId = ownNodeId()
        if (nodeId == 0L) return
        val syncId = (System.nanoTime() and REQUEST_ID_MASK) or 1L
        val request = SyncRequest.newBuilder()
            .setRequesterNodeId(nodeId.toInt())
            .setRequestedTypes(MESH_SYNC_TYPE_MARKERS or MESH_SYNC_TYPE_MESSAGES)
            .setSyncId(syncId.toInt())
            .build()
        val data = MeshWire.encodeData(
            portnum = PORTNUM_AIRTAK_SYNC_REQUEST,
            payload = request.toByteArray(),
        )
        ble.send(BleRepository.OutboundChannel.MeshTx, data)
    }

    private suspend fun handleSyncRequest(packet: MeshRxPacket) {
        val payload = packet.payload ?: return
        val request = runCatching { SyncRequest.parseFrom(payload) }.getOrNull() ?: return
        val syncId = request.syncId.toLong() and UINT32_MASK
        if (syncId != 0L && isSyncIdHandled(syncId)) return
        val requesterId = request.requesterNodeId.toLong() and UINT32_MASK
        if (requesterId == ownNodeId()) return

        val types = request.requestedTypes
        if (types and MESH_SYNC_TYPE_MESSAGES != 0) {
            markSyncIdHandled(syncId)
            rebroadcastRecentMessages()
        }
    }

    private fun isSyncIdHandled(syncId: Long): Boolean =
        synchronized(handledSyncIds) { handledSyncIds.containsKey(syncId) }

    private fun markSyncIdHandled(syncId: Long) {
        if (syncId == 0L) return
        synchronized(handledSyncIds) {
            handledSyncIds[syncId] = System.currentTimeMillis() + MESH_SYNC_ID_TTL_MS
        }
    }

    private suspend fun rebroadcastRecentMessages() {
        val messages = messageDao.recentMessages(MESH_SYNC_MESSAGE_COUNT)
        for (msg in messages) {
            if (!msg.outgoing) continue
            val text = msg.text
            if (text.length > MESH_MAX_TEXT_BYTES) continue
            sendText(channel = msg.channelIndex, text = text, bestEffort = true)
            delay(MESH_SYNC_SPACING_MS)
        }
    }

    fun sendOverlayAlignment(alignment: OverlayAlignment): SendResult {
        val requestId = (System.nanoTime() and REQUEST_ID_MASK) or 1L
        val data = MeshWire.encodeData(
            portnum = PORTNUM_AIRTAK_OVERLAY_ALIGNMENT,
            payload = alignment.toByteArray(),
            wantResponse = true,
            requestId = requestId,
        )
        ble.send(BleRepository.OutboundChannel.MeshTx, data)
        return SendResult.Ok(requestId)
    }

    fun requestMarkerSnapshot(targetNodeId: Long): SendResult {
        val ownId = deviceStatus.status.value?.nodeId?.toLong()?.and(UINT32_MASK) ?: 0L
        if (ownId == 0L || targetNodeId == 0L) return SendResult.Ok(0)
        val syncId = (System.nanoTime() and REQUEST_ID_MASK) or 1L
        val request = MarkerSnapshotRequest.newBuilder()
            .setTargetNodeId((targetNodeId and UINT32_MASK).toInt())
            .setRequesterNodeId(ownId.toInt())
            .setSyncId(syncId.toInt())
            .build()
        val requestId = (System.nanoTime() and REQUEST_ID_MASK) or 1L
        val data = MeshWire.encodeData(
            portnum = PORTNUM_AIRTAK_MARKER_SNAPSHOT_REQUEST,
            payload = request.toByteArray(),
            wantResponse = true,
            destNodeId = targetNodeId,
            requestId = requestId,
        )
        ble.send(BleRepository.OutboundChannel.MeshTx, data)
        return SendResult.Ok(requestId)
    }
}
