// AirTAK Android — data/mesh/MeshWire.kt
// Planning: §8.2 (OTA wire header), §8.3 (Data payload), §8.4 (portnums)
//
// Encodes / decodes the minimum subset of Meshtastic's Data protobuf and the
// AirTAK BLE MESH_RX envelope required by Phase 3. We avoid bringing the full
// vendored Meshtastic protobuf descriptors into the Android build to keep the
// dependency surface small; the Data type only has four fields the app cares
// about today (portnum, payload, want_response, request_id).
//
// MESH_RX envelope (firmware → app, after chunk reassembly):
//   offset  size  field
//   0       1     portnum hint (lower 8 bits of portnum)
//   1       16    OTA header (§8.2)
//   17      N     decrypted Data protobuf
//
// MESH_TX envelope (app → firmware): the bare Data protobuf bytes — the
// firmware wraps them with the OTA header, encryption, and hop accounting.

package com.airtak.data.mesh

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Subset of Meshtastic port numbers we route in Phase 3+; rest are pass-through. */
object Portnum {
    const val TEXT_MESSAGE_APP = 1
    const val POSITION_APP     = 3
    const val NODEINFO_APP     = 4
    const val TELEMETRY_APP    = 67
    const val AIRTAK_PLAYER      = 256
    const val AIRTAK_TACTICAL    = 257
    const val AIRTAK_MODERATION  = 258
    const val AIRTAK_ACK         = 261
    const val AIRTAK_POSREQ      = 262
    const val AIRTAK_AUDIO       = 263
    const val AIRTAK_SYNC_REQUEST = 264
    const val AIRTAK_OVERLAY_ALIGNMENT = 265
    const val AIRTAK_MARKER_SNAPSHOT_REQUEST = 266
}

/** Decoded MESH_RX envelope. */
data class MeshRxPacket(
    val portnumHint: Int,
    val destNodeId: Long,
    val srcNodeId: Long,
    val packetId: Long,
    val flags: Int,
    val channelHash: Int,
    /** Decoded Data.payload bytes (Data proto field 2), null if Data unparseable. */
    val payload: ByteArray?,
    /** Authoritative portnum decoded from Data.portnum (field 1); may differ from hint on overflow. */
    val portnum: Int,
    val wantResponse: Boolean,
    val dataDestNodeId: Long?,
    val dataSourceNodeId: Long?,
    /** Data.request_id (field 6), if present. */
    val requestId: Long?,
) {
    override fun equals(other: Any?): Boolean = other is MeshRxPacket &&
        other.srcNodeId == srcNodeId && other.packetId == packetId
    override fun hashCode(): Int = 31 * srcNodeId.hashCode() + packetId.hashCode()
}

data class MeshtasticPosition(
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Int?,
    val timeUnix: Long?,
    val precisionBits: Int?,
)

data class MeshtasticUser(
    val id: String?,
    val longName: String?,
    val shortName: String?,
)

object MeshWire {

    private const val OTA_HEADER_BYTES = 16

    fun decodeMeshRx(envelope: ByteArray): MeshRxPacket? {
        if (envelope.size < 1 + OTA_HEADER_BYTES) return null
        val portnumHint = envelope[0].toInt() and 0xFF
        val bb = ByteBuffer.wrap(envelope, 1, OTA_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val dest = bb.int.toLong() and 0xFFFF_FFFFL
        val src  = bb.int.toLong() and 0xFFFF_FFFFL
        val id   = bb.int.toLong() and 0xFFFF_FFFFL
        val flags = envelope[1 + 12].toInt() and 0xFF
        val ch    = envelope[1 + 13].toInt() and 0xFF
        val dataBytes = envelope.copyOfRange(1 + OTA_HEADER_BYTES, envelope.size)
        val data = decodeData(dataBytes)
        return MeshRxPacket(
            portnumHint = portnumHint,
            destNodeId = dest, srcNodeId = src, packetId = id,
            flags = flags, channelHash = ch,
            payload = data?.payload,
            portnum = data?.portnum ?: portnumHint,
            wantResponse = data?.wantResponse ?: false,
            dataDestNodeId = data?.destNodeId,
            dataSourceNodeId = data?.sourceNodeId,
            requestId = data?.requestId,
        )
    }

    /** Build a minimal Meshtastic Data protobuf for MESH_TX. */
    fun encodeData(
        portnum: Int,
        payload: ByteArray,
        wantResponse: Boolean = false,
        destNodeId: Long? = null,
        sourceNodeId: Long? = null,
        requestId: Long? = null,
    ): ByteArray {
        val out = ArrayList<Byte>(payload.size + 16)
        // field 1 (portnum) — varint
        writeTag(out, 1, WIRETYPE_VARINT); writeVarint(out, portnum.toLong())
        // field 2 (payload) — length-delimited
        writeTag(out, 2, WIRETYPE_LEN); writeVarint(out, payload.size.toLong()); out.addAll(payload.toList())
        if (wantResponse) {
            // Meshtastic Data.want_response is field 3.
            writeTag(out, 3, WIRETYPE_VARINT); writeVarint(out, 1)
        }
        destNodeId?.let {
            writeTag(out, 4, WIRETYPE_VARINT); writeVarint(out, it and 0xFFFF_FFFFL)
        }
        sourceNodeId?.let {
            writeTag(out, 5, WIRETYPE_VARINT); writeVarint(out, it and 0xFFFF_FFFFL)
        }
        requestId?.let {
            writeTag(out, 6, WIRETYPE_VARINT); writeVarint(out, it and 0xFFFF_FFFFL)
        }
        return out.toByteArray()
    }

    fun decodePosition(payload: ByteArray): MeshtasticPosition? {
        var latitudeE7: Int? = null
        var longitudeE7: Int? = null
        var altitudeM: Int? = null
        var timeUnix: Long? = null
        var precisionBits: Int? = null
        var i = 0
        while (i < payload.size) {
            val (tag, ni) = readVarint(payload, i) ?: return null
            i = ni
            val field = (tag shr 3).toInt()
            val wire = (tag and 0x07L).toInt()
            when (wire) {
                WIRETYPE_VARINT -> {
                    val (v, n2) = readVarint(payload, i) ?: return null
                    i = n2
                    when (field) {
                        3 -> altitudeM = v.toInt()
                        14 -> precisionBits = v.toInt()
                    }
                }
                WIRETYPE_FIXED32 -> {
                    if (i + 4 > payload.size) return null
                    val value = ByteBuffer.wrap(payload, i, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    i += 4
                    when (field) {
                        1 -> latitudeE7 = value
                        2 -> longitudeE7 = value
                        4 -> timeUnix = value.toLong() and 0xFFFF_FFFFL
                    }
                }
                WIRETYPE_LEN -> {
                    val (len, n2) = readVarint(payload, i) ?: return null
                    i = n2 + len.toInt()
                    if (i > payload.size) return null
                }
                WIRETYPE_FIXED64 -> i += 8
                else -> return null
            }
        }
        val lat = latitudeE7 ?: return null
        val lon = longitudeE7 ?: return null
        return MeshtasticPosition(
            latitude = lat / 10_000_000.0,
            longitude = lon / 10_000_000.0,
            altitudeM = altitudeM,
            timeUnix = timeUnix,
            precisionBits = precisionBits,
        )
    }

    fun decodeNodeInfo(payload: ByteArray): MeshtasticUser? {
        var id: String? = null
        var longName: String? = null
        var shortName: String? = null
        var i = 0
        while (i < payload.size) {
            val (tag, ni) = readVarint(payload, i) ?: return null
            i = ni
            val field = (tag shr 3).toInt()
            val wire = (tag and 0x07L).toInt()
            when (wire) {
                WIRETYPE_LEN -> {
                    val (len, n2) = readVarint(payload, i) ?: return null
                    i = n2
                    val end = i + len.toInt()
                    if (end > payload.size) return null
                    val text = payload.copyOfRange(i, end).toString(Charsets.UTF_8)
                    when (field) {
                        1 -> id = text
                        2 -> longName = text
                        3 -> shortName = text
                    }
                    i = end
                }
                WIRETYPE_VARINT -> {
                    val (_, n2) = readVarint(payload, i) ?: return null
                    i = n2
                }
                WIRETYPE_FIXED32 -> i += 4
                WIRETYPE_FIXED64 -> i += 8
                else -> return null
            }
        }
        return if (id == null && longName == null && shortName == null) null else MeshtasticUser(id, longName, shortName)
    }

    /**
     * Decode battery_level from a Meshtastic Telemetry protobuf.
     * Navigates: Telemetry.device_metrics (field 2, LEN) → DeviceMetrics.battery_level (field 2, VARINT).
     * Returns battery percentage 0-100, or null if the field is absent or unparseable.
     */
    fun decodeTelemetryBattery(payload: ByteArray): Int? {
        var i = 0
        while (i < payload.size) {
            val (tag, ni) = readVarint(payload, i) ?: return null
            i = ni
            val field = (tag shr 3).toInt()
            val wire = (tag and 0x07L).toInt()
            when {
                wire == WIRETYPE_LEN && field == 2 -> {
                    val (len, n2) = readVarint(payload, i) ?: return null
                    i = n2
                    val end = i + len.toInt()
                    if (end > payload.size) return null
                    // Inside DeviceMetrics submessage
                    var j = i
                    while (j < end) {
                        val (innerTag, nj) = readVarint(payload, j) ?: return null
                        j = nj
                        val innerField = (innerTag shr 3).toInt()
                        val innerWire = (innerTag and 0x07L).toInt()
                        if (innerWire == WIRETYPE_VARINT && innerField == 2) {
                            val (battery, _) = readVarint(payload, j) ?: return null
                            return battery.toInt().coerceIn(0, 100)
                        }
                        // Skip unknown fields inside DeviceMetrics
                        j = skipField(payload, j, innerWire) ?: return null
                    }
                    i = end
                }
                else -> {
                    i = skipField(payload, i, wire) ?: return null
                }
            }
        }
        return null
    }

    private fun skipField(buf: ByteArray, start: Int, wire: Int): Int? {
        return when (wire) {
            WIRETYPE_VARINT -> {
                val (_, ni) = readVarint(buf, start) ?: return null
                ni
            }
            WIRETYPE_FIXED64 -> (start + 8).takeIf { it <= buf.size }
            WIRETYPE_LEN -> {
                val (len, ni) = readVarint(buf, start) ?: return null
                (ni + len.toInt()).takeIf { it <= buf.size }
            }
            WIRETYPE_FIXED32 -> (start + 4).takeIf { it <= buf.size }
            else -> null
        }
    }

    // ── Minimal Data decoder ──────────────────────────────────────────────────

    private data class DecodedData(
        val portnum: Int,
        val payload: ByteArray,
        val wantResponse: Boolean,
        val destNodeId: Long?,
        val sourceNodeId: Long?,
        val requestId: Long?,
    )

    private fun decodeData(buf: ByteArray): DecodedData? {
        var portnum = 0
        var payload: ByteArray = ByteArray(0)
        var wantResponse = false
        var destNodeId: Long? = null
        var sourceNodeId: Long? = null
        var requestId: Long? = null
        var i = 0
        while (i < buf.size) {
            val (tag, ni) = readVarint(buf, i) ?: return null
            i = ni
            val field = (tag shr 3).toInt()
            val wire = (tag and 0x07L).toInt()
            when (wire) {
                WIRETYPE_VARINT -> {
                    val (v, n2) = readVarint(buf, i) ?: return null
                    i = n2
                    when (field) {
                        1 -> portnum = v.toInt()
                        3 -> wantResponse = v != 0L
                        4 -> destNodeId = if (v == 0L) 0xFFFF_FFFFL else v
                        5 -> sourceNodeId = v
                        6 -> requestId = v
                        else -> { /* skip */ }
                    }
                }
                WIRETYPE_LEN -> {
                    val (len, n2) = readVarint(buf, i) ?: return null
                    i = n2
                    val end = i + len.toInt()
                    if (end > buf.size) return null
                    if (field == 2) payload = buf.copyOfRange(i, end)
                    i = end
                }
                WIRETYPE_FIXED32 -> i += 4
                WIRETYPE_FIXED64 -> i += 8
                else -> return null
            }
        }
        return DecodedData(portnum, payload, wantResponse, destNodeId, sourceNodeId, requestId)
    }

    // ── Protobuf wire primitives ──────────────────────────────────────────────

    private const val WIRETYPE_VARINT = 0
    private const val WIRETYPE_FIXED64 = 1
    private const val WIRETYPE_LEN = 2
    private const val WIRETYPE_FIXED32 = 5

    private fun writeTag(out: MutableList<Byte>, field: Int, wire: Int) {
        writeVarint(out, ((field.toLong() shl 3) or wire.toLong()))
    }

    private fun writeVarint(out: MutableList<Byte>, v: Long) {
        var x = v
        while ((x and 0x7FL.inv()) != 0L) {
            out.add(((x and 0x7FL) or 0x80L).toByte())
            x = x ushr 7
        }
        out.add((x and 0x7FL).toByte())
    }

    /** Returns (value, newIndex) or null on truncation. */
    private fun readVarint(buf: ByteArray, start: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var i = start
        while (i < buf.size) {
            val b = buf[i].toInt() and 0xFF
            i++
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result to i
            shift += 7
            if (shift > 63) return null
        }
        return null
    }
}
