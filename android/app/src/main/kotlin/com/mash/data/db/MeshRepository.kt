// AirTAK Android — data/db/MeshRepository.kt
// Planning: §11, §13.2 | Roadmap: 3.2, 3.3
//
// Parses incoming MESH_RX BLE notifications and persists their content to Room.
// Decodes the firmware MESH_RX envelope (portnum hint + OTA header + decrypted payload) and dispatches
// by portnum:
//   NODEINFO_APP (4)   → parse meshtastic.User proto → upsert NodeEntity
//   POSITION_APP (3)   → parse meshtastic.Position proto → upsert NodeEntity lat/lon
//   TEXT_MESSAGE_APP (1) → parse string payload → insert MessageEntity
//   AIRTAK_PLAYER (256)  → forward to GameRepository
//   AIRTAK_GAME (257)    → forward to GameRepository
//
// NOTE: AES-128-CTR decryption is NOT performed on the Android side in Phase 3.
// The firmware delivers decrypted packet content via BLE MESH_RX, already in
// cleartext (the BLE link itself is LESC-encrypted at the transport layer, §10.6).
// The OTA header is forwarded for informational fields (src_node_id, rssi, snr).

package com.airtak.data.db

import com.airtak.data.ble.BleRepository
import com.airtak.data.game.GameRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/** Meshtastic portnum values (subset used by AirTAK). */
private object Portnum {
    const val TEXT_MESSAGE_APP = 1
    const val POSITION_APP     = 3
    const val NODEINFO_APP     = 4
    const val AIRTAK_PLAYER      = 256
    const val AIRTAK_GAME        = 257
}

private const val MESH_RX_PORTNUM_HINT_BYTES = 1
private const val OTA_HEADER_BYTES = 16
private const val MESH_RX_DATA_OFFSET = MESH_RX_PORTNUM_HINT_BYTES + OTA_HEADER_BYTES

@Singleton
class MeshRepository @Inject constructor(
    private val bleRepository:  BleRepository,
    private val nodeDao:        NodeDao,
    private val messageDao:     MessageDao,
    private val gameRepository: GameRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Call once from MainActivity or Hilt EntryPoint to start processing MESH_RX. */
    fun startProcessing() {
        bleRepository.meshRxFlow()
            .onEach { raw -> scope.launch { processMeshRx(raw) } }
            .launchIn(scope)
    }

    // ── OTA packet dispatcher ─────────────────────────────────────────────────

    private suspend fun processMeshRx(raw: ByteArray) {
        // MESH_RX envelope:
        // [0]     low byte of Data.portnum, for legacy BLE consumers
        // [1:4]   dest_node_id (LE uint32)
        // [5:8]   src_node_id  (LE uint32)
        // [9:12]  packet_id    (LE uint32)
        // [13]    flags: [7:5]=hop_limit [4]=want_ack [3]=via_mqtt [2:0]=hop_start
        // [14]    channel_hash
        // [15]    next_hop_low
        // [16]    relay_node_low
        // [17..]  decrypted Data proto bytes
        if (raw.size < MESH_RX_DATA_OFFSET) return

        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        @Suppress("UNUSED_VARIABLE") val destNodeId = bb.getInt(1).toLong() and 0xFFFFFFFFL
        val srcNodeId = bb.getInt(5).toLong() and 0xFFFFFFFFL
        val payload   = raw.sliceArray(MESH_RX_DATA_OFFSET until raw.size)

        // Parse the meshtastic.Data proto (field 1 = portnum, field 2 = payload bytes).
        val (portnum, data) = parseData(payload) ?: return

        when (portnum) {
            Portnum.NODEINFO_APP     -> onNodeInfo(srcNodeId, data)
            Portnum.POSITION_APP     -> onPosition(srcNodeId, data)
            Portnum.TEXT_MESSAGE_APP -> onTextMessage(srcNodeId, data)
            Portnum.AIRTAK_PLAYER      -> gameRepository.onPlayerPacket(data)
            Portnum.AIRTAK_GAME        -> gameRepository.onGameStatePacket(data)
        }

        // Evict nodes not heard in 24 hours.
        nodeDao.evictStale(System.currentTimeMillis() - 24 * 60 * 60 * 1000L)
    }

    // ── meshtastic.Data parser ─────────────────────────────────────────────────
    // Returns Pair(portnum, inner_payload_bytes) or null on parse error.

    private fun parseData(pb: ByteArray): Pair<Int, ByteArray>? {
        var portnum = 0
        var payload: ByteArray? = null
        var pos = 0
        while (pos < pb.size) {
            val (tagWire, p1) = readVarint(pb, pos) ?: return null
            pos = p1
            val field = (tagWire shr 3).toInt()
            val wire  = (tagWire and 0x07).toInt()
            when {
                field == 1 && wire == 0 -> {
                    val (v, p2) = readVarint(pb, pos) ?: return null
                    portnum = v.toInt(); pos = p2
                }
                field == 2 && wire == 2 -> {
                    val (len, p2) = readVarint(pb, pos) ?: return null
                    val end = p2 + len.toInt()
                    if (end > pb.size) return null
                    payload = pb.sliceArray(p2 until end); pos = end
                }
                wire == 0 -> { val (_, p2) = readVarint(pb, pos) ?: return null; pos = p2 }
                wire == 2 -> {
                    val (len, p2) = readVarint(pb, pos) ?: return null
                    pos = p2 + len.toInt()
                }
                wire == 5 -> { pos += 4 }
                wire == 1 -> { pos += 8 }
                else      -> return null
            }
        }
        return if (payload != null) Pair(portnum, payload) else null
    }

    // ── meshtastic.User → NodeEntity ──────────────────────────────────────────
    // User proto: id(1,string), long_name(2,string), short_name(3,string), hw_model(6,varint).

    private suspend fun onNodeInfo(srcNodeId: Long, pb: ByteArray) {
        var longName = ""
        var hwModel  = 0
        var pos = 0
        while (pos < pb.size) {
            val (tagWire, p1) = readVarint(pb, pos) ?: break
            pos = p1
            val field = (tagWire shr 3).toInt()
            val wire  = (tagWire and 0x07).toInt()
            when {
                field == 2 && wire == 2 -> { longName = readString(pb, pos).also { pos = it.second }.first }
                field == 6 && wire == 0 -> {
                    val (v, p2) = readVarint(pb, pos) ?: break; hwModel = v.toInt(); pos = p2
                }
                wire == 0 -> { val (_, p2) = readVarint(pb, pos) ?: break; pos = p2 }
                wire == 2 -> { val (len, p2) = readVarint(pb, pos) ?: break; pos = p2 + len.toInt() }
                wire == 5 -> { pos += 4 }
                wire == 1 -> { pos += 8 }
                else      -> break
            }
        }
        val existing = nodeDao.findById(srcNodeId)
        nodeDao.upsert(NodeEntity(
            nodeId       = srcNodeId,
            callsign     = longName.takeIf { it.isNotBlank() } ?: existing?.callsign ?: "!%08x".format(srcNodeId),
            teamIndex    = existing?.teamIndex ?: 0,
            squadIndex   = existing?.squadIndex ?: 0,
            latE7        = existing?.latE7,
            lonE7        = existing?.lonE7,
            statusOrdinal = existing?.statusOrdinal ?: 0,
            respawnSecondsRemaining = existing?.respawnSecondsRemaining ?: 0,
            rssi         = existing?.rssi ?: 0f,
            snr          = existing?.snr ?: 0f,
            hwModelOrdinal = hwModel,
            lastSeenMs   = System.currentTimeMillis(),
        ))
    }

    // ── meshtastic.Position → NodeEntity ─────────────────────────────────────
    // Position proto: latitude_i(1,fixed32), longitude_i(2,fixed32), altitude(3,varint).

    private suspend fun onPosition(srcNodeId: Long, pb: ByteArray) {
        var latE7: Int? = null
        var lonE7: Int? = null
        var pos = 0
        while (pos < pb.size) {
            val (tagWire, p1) = readVarint(pb, pos) ?: break
            pos = p1
            val field = (tagWire shr 3).toInt()
            val wire  = (tagWire and 0x07).toInt()
            when {
                field == 1 && wire == 5 -> {
                    if (pos + 4 > pb.size) break
                    latE7 = ByteBuffer.wrap(pb, pos, 4).order(ByteOrder.LITTLE_ENDIAN).getInt(); pos += 4
                }
                field == 2 && wire == 5 -> {
                    if (pos + 4 > pb.size) break
                    lonE7 = ByteBuffer.wrap(pb, pos, 4).order(ByteOrder.LITTLE_ENDIAN).getInt(); pos += 4
                }
                wire == 0 -> { val (_, p2) = readVarint(pb, pos) ?: break; pos = p2 }
                wire == 2 -> { val (len, p2) = readVarint(pb, pos) ?: break; pos = p2 + len.toInt() }
                wire == 5 -> { pos += 4 }
                wire == 1 -> { pos += 8 }
                else      -> break
            }
        }
        val existing = nodeDao.findById(srcNodeId)
        nodeDao.upsert((existing ?: NodeEntity(
            nodeId = srcNodeId,
            callsign = "!%08x".format(srcNodeId),
            teamIndex = 0, squadIndex = 0,
            latE7 = null, lonE7 = null,
            statusOrdinal = 0, respawnSecondsRemaining = 0,
            rssi = 0f, snr = 0f, hwModelOrdinal = 0,
            lastSeenMs = System.currentTimeMillis(),
        )).copy(
            latE7 = latE7 ?: existing?.latE7,
            lonE7 = lonE7 ?: existing?.lonE7,
            lastSeenMs = System.currentTimeMillis(),
        ))
    }

    // ── TEXT_MESSAGE_APP → MessageEntity ──────────────────────────────────────

    private suspend fun onTextMessage(srcNodeId: Long, pb: ByteArray) {
        // TEXT_MESSAGE_APP payload is a raw UTF-8 string (no proto wrapper).
        val text = pb.decodeToString()
        val callsign = nodeDao.findById(srcNodeId)?.callsign ?: "!%08x".format(srcNodeId)
        val msg = MessageEntity(
            channelIndex = 0, // primary channel for now; full channel dispatch in Phase 3.5
            fromNodeId   = srcNodeId,
            fromCallsign = callsign,
            text         = text,
            timestampMs  = System.currentTimeMillis(),
        )
        messageDao.insert(msg)
        messageDao.pruneChannel(0)
    }

    // ── Proto varint decoder ──────────────────────────────────────────────────

    private fun readVarint(buf: ByteArray, startPos: Int): Pair<Long, Int>? {
        var pos = startPos
        var result = 0L
        var shift  = 0
        while (pos < buf.size && shift < 64) {
            val b = buf[pos++].toLong() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0L) return Pair(result, pos)
            shift += 7
        }
        return null
    }

    private fun readString(buf: ByteArray, startPos: Int): Pair<String, Int> {
        val (len, p) = readVarint(buf, startPos) ?: return Pair("", startPos)
        val end = p + len.toInt()
        if (end > buf.size) return Pair("", startPos)
        return Pair(buf.sliceArray(p until end).decodeToString(), end)
    }
}
