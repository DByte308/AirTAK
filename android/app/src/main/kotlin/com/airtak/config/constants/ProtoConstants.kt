// AirTAK Android — config/constants/ProtoConstants.kt
// Planning: §8.2 (OTA wire header), §8.3 (Data payload)
//
// Centralized protobuf wire-format constants: wire types, field numbers,
// bit masks, and field offsets used by the hand-rolled protobuf serialization
// in MeshWire.kt and EspBleOtaEngine.kt.

package com.airtak.config.constants

// ── Wire types (§8.3) ───────────────────────────────────
const val PROTO_WIRE_TYPE_MASK = 0x07L
const val PROTO_FIELD_NUM_SHIFT = 3
const val PROTO_WIRE_VARINT = 0
const val PROTO_WIRE_FIXED64 = 1
const val PROTO_WIRE_LEN = 2
const val PROTO_WIRE_FIXED32 = 5

// ── Varint encoding ─────────────────────────────────────
const val PROTO_VARINT_DATA_MASK = 0x7F
const val PROTO_VARINT_DATA_MASK_L = 0x7FL
const val PROTO_VARINT_CONTINUE_MASK = 0x80
const val PROTO_VARINT_CONTINUE_MASK_L = 0x80L
const val PROTO_VARINT_SHIFT = 7
const val PROTO_VARINT_MAX_SHIFT = 63
const val PROTO_VARINT_MAX_BYTES = 10

// ── Fixed-size wire type byte counts ────────────────────
const val PROTO_FIXED32_BYTES = 4
const val PROTO_FIXED64_BYTES = 8

// ── Data protobuf field numbers (§8.3) ──────────────────
const val DATA_FIELD_PORTNUM = 1
const val DATA_FIELD_PAYLOAD = 2
const val DATA_FIELD_WANT_RESPONSE = 3
const val DATA_FIELD_DEST = 4
const val DATA_FIELD_SOURCE = 5
const val DATA_FIELD_REQUEST_ID = 6

// ── Position protobuf field numbers ─────────────────────
const val POSITION_FIELD_LATITUDE_E7 = 1
const val POSITION_FIELD_LONGITUDE_E7 = 2
const val POSITION_FIELD_ALTITUDE = 3
const val POSITION_FIELD_TIME = 4
const val POSITION_FIELD_PRECISION_BITS = 14
const val POSITION_E7_DIVISOR = 10_000_000.0

// ── NodeInfo protobuf field numbers ─────────────────────
const val NODEINFO_FIELD_ID = 1
const val NODEINFO_FIELD_LONG_NAME = 2
const val NODEINFO_FIELD_SHORT_NAME = 3

// ── Routing protobuf field numbers ──────────────────────
const val ROUTING_FIELD_ERROR_REASON = 3

// ── Telemetry protobuf field numbers ────────────────────
const val TELEMETRY_FIELD_DEVICE_METRICS = 2
const val DEVICE_METRICS_FIELD_BATTERY_LEVEL = 2

// ── OTA protobuf field numbers (EspBleOtaEngine) ────────
const val OTA_START_FIELD_IMAGE_SIZE = 1
const val OTA_START_FIELD_CRC32 = 2
const val OTA_FINISH_FIELD_CRC32 = 2
const val OTA_TAG_OTA_START = 0x0A.toByte()
const val OTA_TAG_OTA_FINISH = 0x12.toByte()
val OTA_TAG_STATUS_REQUEST = byteArrayOf(0x1A.toByte(), 0x00.toByte())
const val OTA_VARINT_TAG_IMAGE_SIZE = 0x08.toByte()
const val OTA_VARINT_TAG_CRC32 = 0x10.toByte()

// ── OTA header field offsets (§8.2) ─────────────────────
/** MESH_RX envelope OTA header size in bytes. */
const val OTA_HEADER_BYTES = 16
/** Offset of the flags byte within the OTA header. */
const val OTA_HEADER_FLAGS_OFFSET = 12
/** Offset of the channel_hash byte within the OTA header. */
const val OTA_HEADER_CHANNEL_HASH_OFFSET = 13

// ── Unsigned integer conversions ────────────────────────
const val UINT8_MASK = 0xFF
const val UINT16_MASK = 0xFFFF
const val UINT32_MASK = 0xFFFF_FFFFL
/** Broadcast destination sentinel. */
const val MESH_BROADCAST_ADDR = 0xFFFF_FFFFL
/** Positive-only mask for request IDs (top bit always zero). */
const val REQUEST_ID_MASK = 0x7FFF_FFFFL

// ── Channel slot masking ────────────────────────────────
const val CHANNEL_SLOT_MASK = 0x07
const val CHANNEL_SLOT_MIN = 0
const val CHANNEL_SLOT_MAX = 7

// ── Java/Kotlin hash code primitives ─────────────────────
/** Standard Java hash-code multiplier (Eclipse/IntelliJ convention). */
const val HASH_PRIME = 31
