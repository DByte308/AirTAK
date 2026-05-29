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

import com.airtak.config.constants.DATA_FIELD_DEST
import com.airtak.config.constants.DATA_FIELD_PAYLOAD
import com.airtak.config.constants.DATA_FIELD_PORTNUM
import com.airtak.config.constants.DATA_FIELD_REQUEST_ID
import com.airtak.config.constants.DATA_FIELD_SOURCE
import com.airtak.config.constants.DATA_FIELD_WANT_RESPONSE
import com.airtak.config.constants.DEVICE_METRICS_FIELD_BATTERY_LEVEL
import com.airtak.config.constants.MESH_BROADCAST_ADDR
import com.airtak.config.constants.MESH_ENCODE_ENVELOPE_BYTES
import com.airtak.config.constants.NODEINFO_FIELD_ID
import com.airtak.config.constants.NODEINFO_FIELD_LONG_NAME
import com.airtak.config.constants.NODEINFO_FIELD_SHORT_NAME
import com.airtak.config.constants.OTA_HEADER_BYTES
import com.airtak.config.constants.OTA_HEADER_CHANNEL_HASH_OFFSET
import com.airtak.config.constants.OTA_HEADER_FLAGS_OFFSET
import com.airtak.config.constants.PORTNUM_AIRTAK_BASE
import com.airtak.config.constants.PORTNUM_NODEINFO_APP
import com.airtak.config.constants.PORTNUM_POSITION_APP
import com.airtak.config.constants.PORTNUM_ROUTING_APP
import com.airtak.config.constants.PORTNUM_TELEMETRY_APP
import com.airtak.config.constants.PORTNUM_TEXT_MESSAGE_APP
import com.airtak.config.constants.POSITION_E7_DIVISOR
import com.airtak.config.constants.POSITION_FIELD_ALTITUDE
import com.airtak.config.constants.POSITION_FIELD_LATITUDE_E7
import com.airtak.config.constants.POSITION_FIELD_LONGITUDE_E7
import com.airtak.config.constants.POSITION_FIELD_PRECISION_BITS
import com.airtak.config.constants.POSITION_FIELD_TIME
import com.airtak.config.constants.PROTO_FIELD_NUM_SHIFT
import com.airtak.config.constants.PROTO_FIXED32_BYTES
import com.airtak.config.constants.PROTO_FIXED64_BYTES
import com.airtak.config.constants.PROTO_VARINT_CONTINUE_MASK
import com.airtak.config.constants.PROTO_VARINT_CONTINUE_MASK_L
import com.airtak.config.constants.PROTO_VARINT_DATA_MASK
import com.airtak.config.constants.PROTO_VARINT_DATA_MASK_L
import com.airtak.config.constants.PROTO_VARINT_MAX_SHIFT
import com.airtak.config.constants.PROTO_VARINT_SHIFT
import com.airtak.config.constants.PROTO_WIRE_FIXED32
import com.airtak.config.constants.PROTO_WIRE_FIXED64
import com.airtak.config.constants.PROTO_WIRE_LEN
import com.airtak.config.constants.HASH_PRIME
import com.airtak.config.constants.PROTO_WIRE_TYPE_MASK
import com.airtak.config.constants.PROTO_WIRE_VARINT
import com.airtak.config.constants.ROUTING_FIELD_ERROR_REASON
import com.airtak.config.constants.TELEMETRY_FIELD_DEVICE_METRICS
import com.airtak.config.constants.UINT32_MASK
import com.airtak.config.constants.UINT8_MASK
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
    override fun hashCode(): Int = HASH_PRIME * srcNodeId.hashCode() + packetId.hashCode()
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
    fun decodeMeshRx(envelope: ByteArray): MeshRxPacket? {
        if (envelope.size < 1 + OTA_HEADER_BYTES) return null
        val portnumHint = envelope[0].toInt() and UINT8_MASK
        val bb = ByteBuffer.wrap(envelope, 1, OTA_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val dest = bb.int.toLong() and UINT32_MASK
        val src  = bb.int.toLong() and UINT32_MASK
        val id   = bb.int.toLong() and UINT32_MASK
        val flags = envelope[1 + OTA_HEADER_FLAGS_OFFSET].toInt() and UINT8_MASK
        val ch    = envelope[1 + OTA_HEADER_CHANNEL_HASH_OFFSET].toInt() and UINT8_MASK
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
        val out = ArrayList<Byte>(payload.size + MESH_ENCODE_ENVELOPE_BYTES)
        // field 1 (portnum) — varint
        writeTag(out, DATA_FIELD_PORTNUM, PROTO_WIRE_VARINT); writeVarint(out, portnum.toLong())
        // field 2 (payload) — length-delimited
        writeTag(out, DATA_FIELD_PAYLOAD, PROTO_WIRE_LEN); writeVarint(out, payload.size.toLong()); out.addAll(payload.toList())
        if (wantResponse) {
            // Meshtastic Data.want_response is field 3.
            writeTag(out, DATA_FIELD_WANT_RESPONSE, PROTO_WIRE_VARINT); writeVarint(out, 1)
        }
        destNodeId?.let {
            writeTag(out, DATA_FIELD_DEST, PROTO_WIRE_FIXED32); writeFixed32(out, it)
        }
        sourceNodeId?.let {
            writeTag(out, DATA_FIELD_SOURCE, PROTO_WIRE_FIXED32); writeFixed32(out, it)
        }
        requestId?.let {
            writeTag(out, DATA_FIELD_REQUEST_ID, PROTO_WIRE_FIXED32); writeFixed32(out, it)
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
            val field = (tag shr PROTO_FIELD_NUM_SHIFT).toInt()
            val wire = (tag and PROTO_WIRE_TYPE_MASK).toInt()
            when (wire) {
                PROTO_WIRE_VARINT -> {
                    val (v, n2) = readVarint(payload, i) ?: return null
                    i = n2
                    when (field) {
                        POSITION_FIELD_ALTITUDE -> altitudeM = v.toInt()
                        POSITION_FIELD_PRECISION_BITS -> precisionBits = v.toInt()
                    }
                }
                PROTO_WIRE_FIXED32 -> {
                    if (i + PROTO_FIXED32_BYTES > payload.size) return null
                    val value = ByteBuffer.wrap(payload, i, PROTO_FIXED32_BYTES).order(ByteOrder.LITTLE_ENDIAN).int
                    i += PROTO_FIXED32_BYTES
                    when (field) {
                        POSITION_FIELD_LATITUDE_E7 -> latitudeE7 = value
                        POSITION_FIELD_LONGITUDE_E7 -> longitudeE7 = value
                        POSITION_FIELD_TIME -> timeUnix = value.toLong() and UINT32_MASK
                    }
                }
                PROTO_WIRE_LEN -> {
                    val (len, n2) = readVarint(payload, i) ?: return null
                    i = n2 + len.toInt()
                    if (i > payload.size) return null
                }
                PROTO_WIRE_FIXED64 -> i += PROTO_FIXED64_BYTES
                else -> return null
            }
        }
        val lat = latitudeE7 ?: return null
        val lon = longitudeE7 ?: return null
        return MeshtasticPosition(
            latitude = lat / POSITION_E7_DIVISOR,
            longitude = lon / POSITION_E7_DIVISOR,
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
            val field = (tag shr PROTO_FIELD_NUM_SHIFT).toInt()
            val wire = (tag and PROTO_WIRE_TYPE_MASK).toInt()
            when (wire) {
                PROTO_WIRE_LEN -> {
                    val (len, n2) = readVarint(payload, i) ?: return null
                    i = n2
                    val end = i + len.toInt()
                    if (end > payload.size) return null
                    val text = payload.copyOfRange(i, end).toString(Charsets.UTF_8)
                    when (field) {
                        NODEINFO_FIELD_ID -> id = text
                        NODEINFO_FIELD_LONG_NAME -> longName = text
                        NODEINFO_FIELD_SHORT_NAME -> shortName = text
                    }
                    i = end
                }
                PROTO_WIRE_VARINT -> {
                    val (_, n2) = readVarint(payload, i) ?: return null
                    i = n2
                }
                PROTO_WIRE_FIXED32 -> i += PROTO_FIXED32_BYTES
                PROTO_WIRE_FIXED64 -> i += PROTO_FIXED64_BYTES
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
            val field = (tag shr PROTO_FIELD_NUM_SHIFT).toInt()
            val wire = (tag and PROTO_WIRE_TYPE_MASK).toInt()
            when {
                wire == PROTO_WIRE_LEN && field == TELEMETRY_FIELD_DEVICE_METRICS -> {
                    val (len, n2) = readVarint(payload, i) ?: return null
                    i = n2
                    val end = i + len.toInt()
                    if (end > payload.size) return null
                    // Inside DeviceMetrics submessage
                    var j = i
                    while (j < end) {
                        val (innerTag, nj) = readVarint(payload, j) ?: return null
                        j = nj
                        val innerField = (innerTag shr PROTO_FIELD_NUM_SHIFT).toInt()
                        val innerWire = (innerTag and PROTO_WIRE_TYPE_MASK).toInt()
                        if (innerWire == PROTO_WIRE_VARINT && innerField == DEVICE_METRICS_FIELD_BATTERY_LEVEL) {
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
            PROTO_WIRE_VARINT -> {
                val (_, ni) = readVarint(buf, start) ?: return null
                ni
            }
            PROTO_WIRE_FIXED64 -> (start + PROTO_FIXED64_BYTES).takeIf { it <= buf.size }
            PROTO_WIRE_LEN -> {
                val (len, ni) = readVarint(buf, start) ?: return null
                (ni + len.toInt()).takeIf { it <= buf.size }
            }
            PROTO_WIRE_FIXED32 -> (start + PROTO_FIXED32_BYTES).takeIf { it <= buf.size }
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
            val field = (tag shr PROTO_FIELD_NUM_SHIFT).toInt()
            val wire = (tag and PROTO_WIRE_TYPE_MASK).toInt()
            when (wire) {
                PROTO_WIRE_VARINT -> {
                    val (v, n2) = readVarint(buf, i) ?: return null
                    i = n2
                    when (field) {
                        DATA_FIELD_PORTNUM -> portnum = v.toInt()
                        DATA_FIELD_WANT_RESPONSE -> wantResponse = v != 0L
                        DATA_FIELD_DEST -> destNodeId = if (v == 0L) MESH_BROADCAST_ADDR else v
                        DATA_FIELD_SOURCE -> sourceNodeId = v
                        DATA_FIELD_REQUEST_ID -> requestId = v
                        else -> { /* skip */ }
                    }
                }
                PROTO_WIRE_LEN -> {
                    val (len, n2) = readVarint(buf, i) ?: return null
                    i = n2
                    val end = i + len.toInt()
                    if (end > buf.size) return null
                    if (field == DATA_FIELD_PAYLOAD) payload = buf.copyOfRange(i, end)
                    i = end
                }
                PROTO_WIRE_FIXED32 -> {
                    if (i + PROTO_FIXED32_BYTES > buf.size) return null
                    val v = ByteBuffer.wrap(buf, i, PROTO_FIXED32_BYTES).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and UINT32_MASK
                    i += PROTO_FIXED32_BYTES
                    when (field) {
                        DATA_FIELD_DEST -> destNodeId = if (v == 0L) MESH_BROADCAST_ADDR else v
                        DATA_FIELD_SOURCE -> sourceNodeId = v
                        DATA_FIELD_REQUEST_ID -> requestId = v
                        else -> { /* skip */ }
                    }
                }
                PROTO_WIRE_FIXED64 -> i += PROTO_FIXED64_BYTES
                else -> return null
            }
        }
        return DecodedData(portnum, payload, wantResponse, destNodeId, sourceNodeId, requestId)
    }

    /**
     * Parse `error_reason` (field 3, varint) from a Meshtastic Routing protobuf.
     * Returns null if the payload cannot be parsed.  error_reason 0 = NONE (ACK);
     * non-zero = NAK (delivery failure).
     */
    fun decodeRoutingErrorReason(payload: ByteArray?): Long? {
        if (payload == null || payload.isEmpty()) return null
        var i = 0
        while (i < payload.size) {
            val (tag, ni) = readVarint(payload, i) ?: return null
            i = ni
            val field = (tag shr PROTO_FIELD_NUM_SHIFT).toInt()
            val wire = (tag and PROTO_WIRE_TYPE_MASK).toInt()
            if (wire == PROTO_WIRE_VARINT) {
                val (v, n2) = readVarint(payload, i) ?: return null
                i = n2
                if (field == ROUTING_FIELD_ERROR_REASON) return v
            } else {
                i = skipField(payload, i, wire) ?: return null
            }
        }
        return null
    }

    // ── Protobuf wire primitives ──────────────────────────────────────────────

    private fun writeTag(out: MutableList<Byte>, field: Int, wire: Int) {
        writeVarint(out, ((field.toLong() shl PROTO_FIELD_NUM_SHIFT) or wire.toLong()))
    }

    private fun writeVarint(out: MutableList<Byte>, v: Long) {
        var x = v
        while ((x and PROTO_VARINT_DATA_MASK_L.inv()) != 0L) {
            out.add(((x and PROTO_VARINT_DATA_MASK_L) or PROTO_VARINT_CONTINUE_MASK_L).toByte())
            x = x ushr PROTO_VARINT_SHIFT
        }
        out.add((x and PROTO_VARINT_DATA_MASK_L).toByte())
    }

    private fun writeFixed32(out: MutableList<Byte>, v: Long) {
        val buf = ByteBuffer.allocate(PROTO_FIXED32_BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(v.toInt())
        out.addAll(buf.array().toList())
    }

    /** Returns (value, newIndex) or null on truncation. */
    private fun readVarint(buf: ByteArray, start: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var i = start
        while (i < buf.size) {
            val b = buf[i].toInt() and UINT8_MASK
            i++
            result = result or ((b and PROTO_VARINT_DATA_MASK).toLong() shl shift)
            if (b and PROTO_VARINT_CONTINUE_MASK == 0) return result to i
            shift += PROTO_VARINT_SHIFT
            if (shift > PROTO_VARINT_MAX_SHIFT) return null
        }
        return null
    }
}
