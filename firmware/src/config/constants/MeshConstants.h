// AirTAK — Airsoft Team Awareness Kit
// firmware/src/config/constants/MeshConstants.h — Centralized mesh networking constants
//
// Constants extracted from firmware/src/config/Defaults.h, firmware/src/mesh/Router.h,
// firmware/src/mesh/ChannelManager.h, firmware/src/mesh/PacketPool.h, and
// firmware/src/mesh/MeshNode.cpp.
//
// Categories:
//   Channel Config      — Max slots, channel name length
//   Hop Limits          — Default and per-type hop limits
//   ACK Parameters      — Retries, timeouts, exponential backoff
//   Router              — Duplicate suppression cache
//   OTA Header          — On-air wire header size
//   Packet Pool         — Pool size and buffer limits
//   Node Info           — Broadcast intervals and startup announce
//   Telemetry           — Broadcast interval
//   Remote Nodes        — Max known nodes, name buffer sizes
//   Default PSK         — Meshtastic-compatible default key
//   Position Config     — Smart beaconing defaults
//   Config Constraints  — Validation clamp values

#pragma once
#include <cstdint>
#include <cstddef>

namespace airtak {

// ── Channel Configuration (§8.5) ────────────────────────────────────────────
constexpr uint8_t  MAX_CHANNEL_SLOTS          = 8;     // Maximum number of channel slots
constexpr size_t   CHANNEL_NAME_MAX_BYTES     = 16;    // Max channel name bytes (excl. null)

// ── Default Hop Limits (§8.7) ──────────────────────────────────────────────
constexpr uint8_t HOP_LIMIT_DEFAULT           = 7;     // Default for most packet types
constexpr uint8_t HOP_LIMIT_AUDIO             = 1;     // Audio MUST NOT relay (§12.7)

// ── Application-Layer ACK (§8.9) ─────────────────────────────────────────────
constexpr uint32_t ACK_MAX_RETRIES           = 3;
constexpr uint32_t ACK_RETRY_BASE_MS         = 200;   // Base for exponential backoff
constexpr uint32_t ACK_TIMEOUT_MIN_MS        = 500;   // Minimum ACK timeout
constexpr uint32_t ACK_TIMEOUT_PER_HOP_MS    = 400;   // Per-hop addition to timeout
constexpr uint8_t  ACK_RETRY_QUEUE_SIZE      = 8;     // Max in-flight ACK-eligible packets
constexpr uint8_t  ACK_HOP_START_FALLBACK    = 7;     // Fallback when hop_start=0 (legacy)

// ── Router Duplicate Suppression ────────────────────────────────────────────
constexpr uint32_t ROUTER_DUP_CACHE_SIZE      = 100;   // (source_id, packet_id) pairs
constexpr uint32_t ROUTER_DUP_CACHE_TTL_MS    = 60000; // 60 seconds per entry

// ── OTA Header (§8.2) ───────────────────────────────────────────────────────
constexpr uint8_t  OTA_HEADER_BYTES           = 16;    // Size of OTA wire header

// ── Packet Pool (§9.2) ──────────────────────────────────────────────────────
constexpr size_t   PACKET_POOL_SIZE           = 40;    // Max concurrent live packets
constexpr size_t   PACKET_POOL_BUF_SIZE       = 256;   // Max on-air packet payload size

// ── Node Info Broadcast ─────────────────────────────────────────────────────
constexpr uint32_t NODEINFO_BROADCAST_INTERVAL_SECS = 900;   // 15 minutes
constexpr uint32_t STARTUP_ANNOUNCE_DELAY_MS        = 10000; // 10 seconds after boot

// ── Telemetry Interval ──────────────────────────────────────────────────────
constexpr uint32_t TELEMETRY_BROADCAST_INTERVAL_SECS = 900; // 15 minutes

// ── Remote Node Tracking ────────────────────────────────────────────────────
constexpr uint8_t  MAX_KNOWN_NODES            = 50;    // Max nodes tracked in mesh
constexpr size_t   REMOTE_NODE_LONG_NAME_MAX  = 40;    // Long name buffer for remote nodes
constexpr size_t   REMOTE_NODE_SHORT_NAME_MAX = 5;     // Short name buffer for remote nodes

// MESHTASTIC_DEFAULT_PSK_BYTES — now in CryptoConstants.h (§15.1)

// ── Position Config Defaults (§8.7 smart beaconing) ─────────────────────────
constexpr uint32_t POSITION_BROADCAST_INTERVAL_SECS  = 90;   // Max heartbeat interval
constexpr uint32_t POSITION_GPS_PRECISION_BITS        = 32;   // Full precision
constexpr uint32_t POSITION_MOVEMENT_THRESHOLD_M      = 10;   // Min movement to trigger TX
constexpr uint32_t POSITION_MIN_TX_INTERVAL_SECS      = 15;   // Minimum gap between TX
constexpr uint32_t POSITION_HIT_HEARTBEAT_SECS        = 300;  // Heartbeat when HIT/RESPAWNING/ELIM

// ── Config Field Constraint Constants ───────────────────────────────────────
// (Values used in ConfigManager.cpp for clamping/validation)
constexpr uint8_t  CONFIG_MAX_BRIGHTNESS_PCT    = 100;   // Max brightness percentage
constexpr uint8_t  CONFIG_MAX_HOP_LIMIT         = 7;     // Max hop limit value
constexpr uint8_t  CONFIG_MAX_GPS_SOURCE        = 1;     // Max GPS source enum value

// ── OTA Header Flag Bit Positions (§8.2) ───────────────────────────────────
// flags byte layout: [7:5]=hop_start [4]=via_mqtt [3]=want_ack [2:0]=hop_limit
constexpr uint8_t  OTA_FLAGS_HOP_LIMIT_MASK   = 0x07;  // Bits [2:0]: hop_limit
constexpr uint8_t  OTA_FLAGS_WANT_ACK_BIT     = 3;     // Bit 3: want_ack
constexpr uint8_t  OTA_FLAGS_WANT_ACK_MASK    = 0x08;  // Bitmask: want_ack
constexpr uint8_t  OTA_FLAGS_HOP_START_SHIFT  = 5;     // Bits [7:5]: hop_start
constexpr uint8_t  OTA_FLAGS_HOP_START_MASK   = 0x07;  // Bitmask (shifted): hop_start

// ── Protobuf Wire Types ───────────────────────────────────────────────────
constexpr uint8_t  PROTO_WIRE_TYPE_MASK        = 0x07;   // Protobuf wire type mask (lower 3 bits)
constexpr uint8_t  PROTO_WIRE_VARINT           = 0;      // Protobuf wire type: varint
constexpr uint8_t  PROTO_WIRE_I64              = 1;      // Protobuf wire type: 64-bit
constexpr uint8_t  PROTO_WIRE_LEN              = 2;      // Protobuf wire type: length-delimited
constexpr uint8_t  PROTO_WIRE_I32              = 5;      // Protobuf wire type: 32-bit

// ── Protobuf Field Numbers ─────────────────────────────────────────────────
// NodeInfo (User protobuf, Meshtastic-compatible)
constexpr uint32_t NODEINFO_FIELD_ID            = 1;    // string id
constexpr uint32_t NODEINFO_FIELD_LONG_NAME     = 2;    // string long_name
constexpr uint32_t NODEINFO_FIELD_SHORT_NAME    = 3;    // string short_name
constexpr uint32_t NODEINFO_FIELD_MACADDR       = 4;    // bytes macaddr
constexpr uint32_t NODEINFO_FIELD_HW_MODEL      = 5;    // varint hw_model
constexpr uint32_t NODEINFO_FIELD_IS_LICENSED   = 6;    // varint is_licensed
constexpr uint32_t NODEINFO_FIELD_ROLE          = 7;    // varint role
constexpr uint32_t NODEINFO_FIELD_PUBLIC_KEY    = 8;    // bytes public_key

// Position protobuf fields
constexpr uint32_t POSITION_FIELD_ALTITUDE       = 3;   // varint altitude
constexpr uint32_t POSITION_FIELD_PRECISION_BITS = 23;  // varint precision_bits

// DeviceMetrics protobuf fields
constexpr uint32_t DEVICEMETRICS_FIELD_BATTERY   = 1;   // varint battery_level

// Telemetry wrapper field (used when wrapping DeviceMetrics for TX)
constexpr uint32_t TELEMETRY_FIELD_DEVICE_METRICS = 2; // length-delimited device_metrics

// MESHTASTIC_ROLE_CLIENT — now in SystemConstants.h

// ── Router Relay Queue ────────────────────────────────────────────────────
constexpr uint8_t  ROUTER_RELAY_QUEUE_SIZE     = 16;    // Pending relay queue

// ── Mesh Addressing ────────────────────────────────────────────────────────
constexpr uint32_t MESH_BROADCAST_ADDR         = 0xFFFFFFFF; // Broadcast destination

// ── Initial Packet ID ──────────────────────────────────────────────────────
constexpr uint32_t PACKET_ID_INITIAL           = 1;     // First packet ID after boot

// ── Channel Slot Migration ────────────────────────────────────────────────
// Default slot name for the primary channel (Meshtastic LONG_FAST)
constexpr const char* CHANNEL_NAME_LONGFAST    = "LongFast";
// Index into PSK where the single-byte PSK index is placed
constexpr uint8_t  PSK_INDEX_BYTE              = 0x01;  // Default PSK index "1"
constexpr size_t   PSK_INDEX_BYTE_LEN          = 1;     // Single-byte PSK index length
// Byte offset for PSK expansion (last byte of 16-byte default key)
constexpr uint8_t  PSK_EXPANSION_BYTE_OFFSET   = 15;

// ── Position Defaults ──────────────────────────────────────────────────────
constexpr uint8_t  POSITION_GPS_SOURCE_DEF     = 0;     // 0=DEVICE_GPS, 1=PHONE_GPS
constexpr uint8_t  POSITION_CHANNEL_INDEX_DEF  = 0;     // Default channel for POSITION_APP
constexpr bool     POSITION_SMART_BROADCAST_DEF = true; // Smart beaconing default

// ── Security Defaults ─────────────────────────────────────────────────────
constexpr uint32_t SECURITY_MAX_BONDED_DEF     = 1;     // Default max bonded devices

// ── Channel Slot Protobuf Field Numbers (§8.5) ─────────────────────────────
constexpr uint32_t CHANNELCONFIG_FIELD_SLOTS   = 1;     // repeated ChannelSlot slots
constexpr uint32_t CHANNELSLOT_FIELD_INDEX     = 1;     // uint32 index
constexpr uint32_t CHANNELSLOT_FIELD_NAME      = 2;     // string name
constexpr uint32_t CHANNELSLOT_FIELD_PSK       = 3;     // bytes psk
constexpr uint32_t CHANNELSLOT_FIELD_ROLE      = 4;     // ChannelRole role
constexpr uint32_t CHANNELSLOT_FIELD_UPLINK    = 5;     // bool uplink_enabled
constexpr uint32_t CHANNELSLOT_FIELD_DOWNLINK  = 6;     // bool downlink_enabled
constexpr uint32_t CHANNELSLOT_FIELD_PRECISION = 7;     // uint32 position_precision
constexpr uint32_t CHANNELSLOT_FIELD_PHY       = 8;     // uint32 phy_profile_index

// ── Channel Buffer Size ────────────────────────────────────────────────────
// CHANNEL_NAME_MAX_BYTES (16) + 1 for null terminator
constexpr size_t   CHANNEL_NAME_BUFFER_SIZE    = 17;

// ── MESHTASTIC_ROLE_CLIENT is now in SystemConstants.h ─────────────────
// (already defined above)

// ── NodeInfo Protobuf Field Numbers ──────────────────────────────────────
constexpr uint32_t NODEINFO_FIELD_ID_STR        = 1;     // (same as NODEINFO_FIELD_ID, alias)
constexpr uint32_t NODEINFO_FIELD_LONG_NAME_STR = 2;     // (same as NODEINFO_FIELD_LONG_NAME)
constexpr uint32_t NODEINFO_FIELD_SHORT_NAME_STR= 3;     // (same as NODEINFO_FIELD_SHORT_NAME)
constexpr uint32_t NODEINFO_FIELD_PUBLIC_KEY_BYTES= 8;   // (same as NODEINFO_FIELD_PUBLIC_KEY)

// ── Position Protobuf Field Numbers ──────────────────────────────────────
constexpr uint32_t POSITION_FIELD_LATITUDE       = 1;
constexpr uint32_t POSITION_FIELD_LONGITUDE      = 2;
constexpr uint32_t POSITION_FIELD_TIME           = 4;

// ── DeviceMetrics Protobuf Field Numbers ─────────────────────────────────
constexpr uint32_t DEVICEMETRICS_FIELD_VOLTAGE   = 2;

// ── PlayerStatePacket Protobuf Field Numbers ─────────────────────────────
constexpr uint32_t PLAYERSTATE_FIELD_IS_STATE_TRANSITION = 7;

// ── TextMessage Protobuf Field Numbers ───────────────────────────────────
constexpr uint32_t TEXTMSG_FIELD_TEXT                    = 1;     // TextMessage.text

// ── Config Protobuf Field Numbers ────────────────────────────────────────
constexpr uint32_t DEVICECONFIG_FIELD_LONG_NAME    = 1;
constexpr uint32_t DEVICECONFIG_FIELD_SHORT_NAME   = 2;
constexpr uint32_t GAMECONFIG_FIELD_CALLSIGN       = 1;
constexpr uint32_t POWERCONFIG_FIELD_DISPLAY_OFF    = 4;
constexpr uint32_t POWERCONFIG_FIELD_BRIGHTNESS     = 5;
constexpr uint32_t POWERCONFIG_FIELD_ADC_MULTIPLIER = 6;
constexpr uint32_t POSITIONCONFIG_FIELD_INTERVAL   = 1;
constexpr uint32_t POSITIONCONFIG_FIELD_MIN_TX     = 5;
constexpr uint32_t POSITIONCONFIG_FIELD_MOVEMENT   = 4;
constexpr uint32_t POSITIONCONFIG_FIELD_GPS_PREC   = 2;
constexpr uint32_t POSITIONCONFIG_FIELD_HOP_LIMIT  = 6;
constexpr uint32_t POSITIONCONFIG_FIELD_GPS_SRC    = 7;
constexpr uint32_t POSITIONCONFIG_FIELD_CH_INDEX   = 8;
constexpr uint32_t LORACONFIG_FIELD_REGION        = 1;
constexpr uint32_t LORACONFIG_FIELD_TX_POWER       = 2;

// ── Buffer Sizes ─────────────────────────────────────────────────────────
constexpr size_t   NODEINFO_BUF_SIZE             = 128;   // Serialized NodeInfo proto buffer
constexpr size_t   DEVICEMETRICS_BUF_SIZE        = 24;    // Serialized DeviceMetrics buffer
constexpr size_t   CURVE25519_KEYPAIR_TOTAL_BYTES = 64;   // pubkey(32) + privkey(32)
constexpr size_t   TEXT_MESSAGE_MAX_BYTES        = 256;   // Text message payload buffer
constexpr size_t   ADMIN_ERROR_MSG_MAX_BYTES     = 96;    // Admin error message buffer
constexpr size_t   TRACEROUTE_RESP_BUF_SIZE      = 80;    // Traceroute response buffer
constexpr size_t   ACK_BUF_SIZE                  = 64;    // ACK message buffer
constexpr size_t   ACK_INNER_BUF_SIZE            = 32;    // ACK inner payload buffer

// ── Conversion/Scaling ───────────────────────────────────────────────────
constexpr float    BATTERY_LEVEL_TO_PCT          = 100.0f; // Float 0.0-1.0 → integer percentage
constexpr float    MV_TO_VOLTS                   = 1000.0f;// Millivolts to volts
constexpr uint32_t STARTUP_BURST_SAFETY_MARGIN_MS = 1000;  // Safety margin for startup burst

// ── SNR Relay Delay ──────────────────────────────────────────────────────
constexpr float    SNR_RELAY_DELAY_FACTOR        = 50.0f;  // Ms per 1.0 dB below SNR max
constexpr uint32_t SNR_RELAY_DELAY_JITTER_MAX_MS = 50;     // Random jitter added to relay delay

// ── ACK Timeout ──────────────────────────────────────────────────────────
constexpr uint32_t ACK_TIMEOUT_HOP_MULTIPLIER    = 2;      // Per-hop multiplier for ACK timeout

// ── Packet ID Seeding ────────────────────────────────────────────────────
constexpr uint32_t PACKET_ID_SEED_NODEID_SHIFT   = 8;     // node_id shift for packet ID seed
constexpr uint32_t PACKET_ID_SEED_RANDOM_RANGE   = 256;   // Random byte range for packet ID seed

// ── Admin Message Field Range ────────────────────────────────────────────
constexpr uint32_t ADMIN_MSG_REQ_FIELD_MIN       = 26;
constexpr uint32_t ADMIN_MSG_REQ_FIELD_MAX       = 40;

// ── Message Screen ───────────────────────────────────────────────────────
constexpr uint8_t  MSG_RING_BUFFER_SIZE          = 8;     // Message history capacity
constexpr uint8_t  MSG_DISPLAY_COUNT             = 3;     // Messages shown on screen
constexpr uint8_t  MSG_CALLSIGN_DISPLAY_CHARS    = 8;     // Callsign chars shown
constexpr uint8_t  MSG_TEXT_DISPLAY_CHARS        = 47;    // Message text chars shown

// ── Channel Migration ────────────────────────────────────────────────────
constexpr const char* CHANNEL_DEFAULT_NAME_OLD  = "AirTAK";  // Pre-migration default name

// ── Protobuf Wire Format Constants ───────────────────────────────────────
constexpr uint8_t  PROTO_VARINT_CONTINUE_MASK   = 0x80;   // Varint continuation bit
constexpr uint8_t  PROTO_VARINT_DATA_MASK       = 0x7F;   // Varint data bits (lower 7)
constexpr uint8_t  PROTO_FIELD_NUM_SHIFT        = 3;      // Field number is upper bits of tag
constexpr uint8_t  PROTO_FIXED32_BYTES          = 4;      // protobuf fixed32 field size
constexpr uint8_t  PROTO_FIXED64_BYTES          = 8;      // protobuf fixed64 field size

// ── Encoded Protobuf Tag Bytes (for statically-encoded ACK messages) ─────
// tag=1,wire=0(varint),value=5 → portnum=ROUTING_APP
constexpr uint8_t  PROTO_ENC_PORTNUM_ROUTING     = 0x05;
// tag=1,wire=0(varint),value=261 → portnum=AIRTAK_ACK
constexpr uint8_t  PROTO_ENC_PORTNUM_AIRTAK_ACK  = 0x85;
constexpr uint8_t  PROTO_ENC_PORTNUM_AIRTAK_ACK2 = 0x02;
// tag=2,wire=2(len), value=2 → Data.payload length 2
constexpr uint8_t  PROTO_ENC_PAYLOAD_TAG         = 0x12;
constexpr uint8_t  PROTO_ENC_PAYLOAD_LEN         = 0x02;
// tag=3,wire=0(varint),value=0 → error_reason=NONE
constexpr uint8_t  PROTO_ENC_ERROR_TAG           = 0x18;
constexpr uint8_t  PROTO_ENC_ERROR_NONE          = 0x00;
// tag=6,wire=5(fixed32) → Data.request_id
constexpr uint8_t  PROTO_ENC_REQUEST_ID_TAG      = 0x35;

} // namespace airtak
