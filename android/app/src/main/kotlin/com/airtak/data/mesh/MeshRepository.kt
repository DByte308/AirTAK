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
import com.airtak.data.db.MessageDao
import com.airtak.data.db.MessageEntity
import com.airtak.proto.AdminPacket
import com.airtak.proto.PlayerStatePacket
import com.airtak.proto.PositionRequestPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MeshRepository @Inject constructor(
    private val ble: BleRepository,
    private val messageDao: MessageDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** All decoded mesh packets, regardless of portnum. */
    private val _packets = MutableSharedFlow<MeshRxPacket>(extraBufferCapacity = 128)
    val packets: SharedFlow<MeshRxPacket> = _packets.asSharedFlow()

    init {
        scope.launch {
            ble.inbound
                .filter { it.channel == InboundChannel.MeshRx }
                .collect { frame ->
                    val pkt = MeshWire.decodeMeshRx(frame.payload) ?: return@collect
                    _packets.emit(pkt)
                    if (pkt.portnum == Portnum.TEXT_MESSAGE_APP && pkt.payload != null) {
                        persistIncomingText(pkt)
                    }
                }
        }
    }

    /**
     * Send a UTF-8 text message on the primary channel. The byte-count cap is
     * enforced by the caller (Messages screen) and is also rechecked here so
     * callers cannot accidentally exceed the 237-byte LoRa payload (§11.6).
     */
    fun sendText(channel: Int, text: String): SendResult {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_TEXT_BYTES) return SendResult.TooLong(bytes.size)
        val data = MeshWire.encodeData(Portnum.TEXT_MESSAGE_APP, bytes)
        ble.send(BleRepository.OutboundChannel.MeshTx, data)
        scope.launch {
            messageDao.insert(
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
            messageDao.pruneOldest(channel, MESSAGE_WINDOW)
        }
        return SendResult.Ok
    }

    fun sendPlayerState(packet: PlayerStatePacket): SendResult {
        val data = MeshWire.encodeData(
            portnum = Portnum.AIRTAK_PLAYER,
            payload = packet.toByteArray(),
            wantResponse = packet.isStateTransition,
        )
        ble.send(BleRepository.OutboundChannel.MeshTx, data)
        return SendResult.Ok
    }

    fun sendAdminPacket(packet: AdminPacket): SendResult {
        val data = MeshWire.encodeData(
            portnum = Portnum.AIRTAK_ADMIN,
            payload = packet.toByteArray(),
            wantResponse = true,
        )
        ble.send(BleRepository.OutboundChannel.MeshTx, data)
        return SendResult.Ok
    }

    fun requestPosition(targetNodeId: Long, requesterNodeId: Long): SendResult {
        val request = PositionRequestPacket.newBuilder()
            .setTargetNodeId((targetNodeId and 0xFFFF_FFFFL).toInt())
            .setRequesterNodeId((requesterNodeId and 0xFFFF_FFFFL).toInt())
            .build()
        val data = MeshWire.encodeData(Portnum.AIRTAK_POSREQ, request.toByteArray())
        ble.send(BleRepository.OutboundChannel.MeshTx, data)
        return SendResult.Ok
    }

    fun observeMessages(channel: Int): Flow<List<MessageEntity>> = messageDao.observe(channel)

    sealed interface SendResult {
        data object Ok : SendResult
        data class TooLong(val attemptedBytes: Int) : SendResult
    }

    private suspend fun persistIncomingText(pkt: MeshRxPacket) {
        val text = pkt.payload?.toString(Charsets.UTF_8) ?: return
        val channel = if (pkt.channelHash == 0) 0 else 1   // crude two-channel default; refined in Phase 4
        messageDao.insert(
            MessageEntity(
                channelIndex = channel,
                sourceNodeId = pkt.srcNodeId,
                sourceCallsign = "",     // resolved once NODEINFO_APP wiring lands (Phase 4)
                text = text,
                timestampMs = System.currentTimeMillis(),
                outgoing = false,
            ),
        )
        messageDao.pruneOldest(channel, MESSAGE_WINDOW)
    }

    companion object {
        const val MAX_TEXT_BYTES = 237        // LoRa max payload after headers (§11.6)
        const val MESSAGE_WINDOW = 1_000      // rolling per-channel window
    }
}
