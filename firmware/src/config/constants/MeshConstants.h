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

// ── Default PSK (Meshtastic-compatible default "1") ─────────────────────────
constexpr size_t   MESHTASTIC_DEFAULT_PSK_BYTES = 16;  // 16-byte default key "AQ=="

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

// Client role constant
constexpr uint32_t MESHTASTIC_ROLE_CLIENT         = 0;  // CLIENT device role

} // namespace airtak
