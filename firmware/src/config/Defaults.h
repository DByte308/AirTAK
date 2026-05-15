// MASH — Mesh Airsoft Situational Hub
// firmware/src/config/Defaults.h — Factory default values for all config groups
// Planning: §9.4.1, §8.1, §8.7, §14.2 | Roadmap: 1.6, 2.9
//
// These values are loaded when no config file exists on LittleFS (first boot).
// All configurable values that have a runtime override live here — not in config.h.
// Constants that are truly fixed hardware constraints live in firmware/include/config.h.

#pragma once

#include <cstdint>

namespace mash {
namespace defaults {

// ── DeviceConfig ──────────────────────────────────────────────────────────────
constexpr const char* DEVICE_LONG_NAME  = "MASH Node";
constexpr const char* DEVICE_SHORT_NAME = "MASH";

// ── LoraConfig ────────────────────────────────────────────────────────────────
// Region: 0=EU_868, 1=US_915. Set at first-time setup via Settings > LoRa.
constexpr uint8_t  LORA_REGION        = 0;    // EU_868 default
constexpr uint8_t  LORA_TX_POWER_DBM  = 22;   // SX1262 maximum

// PHY Profile defaults (indexes 0–4). See §8.1 for SF/BW/CR semantics.
// All five profiles share sync word 0x2B, preamble 16, explicit header, CRC on, IQ normal.
struct PhyProfileDefault {
    const char* name;
    uint8_t     spreading_factor;
    uint32_t    bandwidth_hz;
    uint8_t     coding_rate;   // 5=4/5  6=4/6  7=4/7  8=4/8
};

constexpr PhyProfileDefault PHY_PROFILES[5] = {
    // [0] Long/Mesh:     ~0.9 kbps, 3–8 km LOS, ~310 ms ToA/50B — Meshtastic LONG_FAST
    { "Long/Mesh",      11, 250000, 5 },
    // [1] Short/Speed:   ~5.5 kbps, 0.5–2 km LOS, ~38 ms ToA/50B — Meshtastic SHORT_FAST; audio-capable
    { "Short/Speed",     7, 250000, 5 },
    // [2] Long/Reliable: ~0.2 kbps, 5–15 km LOS, ~2700 ms ToA/50B — Meshtastic LONG_SLOW
    { "Long/Reliable",  12, 125000, 8 },
    // [3] Medium/Slow:   ~1.9 kbps, 2–5 km LOS, ~150 ms ToA/50B — Meshtastic SHORT_SLOW (§8.1 v1.5)
    { "Medium/Slow",    10, 250000, 5 },
    // [4] User Defined:  all fields must be set by user
    { "User Defined",    0,      0, 0 },
};

// ── ChannelConfig — 8 slots (§8.5) ───────────────────────────────────────────
// PSK must be set by user at first-time setup. Empty = no encryption (insecure).
constexpr uint8_t CHANNEL_COUNT = 8;

struct ChannelSlotDefault {
    uint8_t     index;
    const char* name;
    uint8_t     role;                // 0=DISABLED 1=PRIMARY 2=SECONDARY
    bool        uplink_enabled;
    bool        downlink_enabled;
    uint8_t     position_precision;  // 0–32
    uint8_t     phy_profile_index;
};

constexpr ChannelSlotDefault CHANNEL_SLOTS[8] = {
    // Slot 0: PRIMARY — Meshtastic-compatible + MASH game traffic
    { 0, "MASH",  1, true,  true,  32, 0 },
    // Slot 1: SECONDARY — MASH game-only traffic (separate PSK)
    { 1, "Game",  2, true,  true,  32, 0 },
    // Slot 2: DISABLED — pre-configured for Phase 7 PTT audio (Profile 1: Short/Speed)
    { 2, "Audio", 0, true,  true,  32, 1 },
    // Slots 3–7: DISABLED
    { 3, "Ch 3",  0, false, false, 32, 0 },
    { 4, "Ch 4",  0, false, false, 32, 0 },
    { 5, "Ch 5",  0, false, false, 32, 0 },
    { 6, "Ch 6",  0, false, false, 32, 0 },
    { 7, "Ch 7",  0, false, false, 32, 0 },
};

// ── PositionConfig — Smart beaconing (§8.7) ───────────────────────────────────
constexpr uint32_t POSITION_BROADCAST_INTERVAL_SECS = 90;    // heartbeat max
constexpr uint32_t POSITION_GPS_PRECISION_BITS       = 32;    // full precision
constexpr bool     POSITION_SMART_BROADCAST          = true;
constexpr uint32_t POSITION_MOVEMENT_THRESHOLD_M     = 10;    // fixed; not user-configurable
constexpr uint32_t POSITION_MIN_TX_INTERVAL_SECS     = 15;    // min gap between TX
constexpr uint32_t POSITION_HIT_HEARTBEAT_SECS       = 300;   // 5 min when HIT/RESPAWNING/ELIMINATED

// ── Per-packet-type default hop limits (§8.7, updated v1.5) ────────────────────
// All types use hop_limit=7 (maximum) except MASH_AUDIO=1 and MASH_ACK (dynamic).
// Rationale: hop_limit does not create extra relay traffic — relay count is bounded
// by mesh topology. Restricting hop_limit only prevents delivery to far-edge nodes.
// Airtime is controlled by smart beaconing TX interval, not hop_limit fields.
// POSITION_APP can be overridden via PositionConfig.position_hop_limit.
constexpr uint8_t HOP_LIMIT_TEXT_MESSAGE     = 7;
constexpr uint8_t HOP_LIMIT_POSITION         = 7;
constexpr uint8_t HOP_LIMIT_NODEINFO         = 7;
constexpr uint8_t HOP_LIMIT_TELEMETRY        = 7;
constexpr uint8_t HOP_LIMIT_MASH_PLAYER      = 7;
constexpr uint8_t HOP_LIMIT_MASH_GAME        = 7;
constexpr uint8_t HOP_LIMIT_MASH_ZONE        = 7;
constexpr uint8_t HOP_LIMIT_MASH_OBJECTIVE   = 7;
constexpr uint8_t HOP_LIMIT_MASH_ADMIN       = 7;
constexpr uint8_t HOP_LIMIT_MASH_ACK         = 0;  // Set by receiver to original packet's hop_start (§8.9)
constexpr uint8_t HOP_LIMIT_POSITION_REQUEST = 7;
constexpr uint8_t HOP_LIMIT_MASH_AUDIO       = 1;  // MUST never relay audio — sole exception (§12.7)

// ── PowerConfig ───────────────────────────────────────────────────────────────
constexpr bool     POWER_GPS_DUTY_CYCLE    = false;  // disabled until Phase 6
constexpr uint32_t POWER_GPS_ON_SECS       = 30;
constexpr uint32_t POWER_GPS_OFF_SECS      = 60;
constexpr uint32_t POWER_DISPLAY_OFF_SECS  = 0;      // 0 = never off

// ── GameConfig ────────────────────────────────────────────────────────────────
constexpr const char* GAME_CALLSIGN         = "Player";
constexpr uint32_t    GAME_TEAM_ID          = 0;    // 0 = SPECTATOR until user sets a team
constexpr uint32_t    GAME_PLAYER_COLOR_RGB = 0x2196F3; // Material Blue
constexpr bool        GAME_IS_ADMIN         = false;
constexpr uint32_t    RESPAWN_TIMER_SECS    = 30;   // seconds to respawn after HIT cancel window expires (§12.6)

// ── SecurityConfig ────────────────────────────────────────────────────────────
constexpr uint32_t SECURITY_MAX_BONDED_DEVICES = 1;

// ── AudioConfig (Phase 7) ─────────────────────────────────────────────────────
constexpr uint32_t AUDIO_CHANNEL_SLOT  = 2;    // slot 2 (Profile 1, Short/Speed)
constexpr uint32_t AUDIO_CODEC2_BPS    = 1300; // 1300 bps mode (§12.7)
constexpr bool     AUDIO_PTT_ENABLED   = false; // disabled until user opts in
constexpr uint32_t AUDIO_FRAME_COUNT   = 8;    // 8 frames = 160 ms; EU g1 requires ≥ 42

// ── Application-layer reliability (§8.9) ────────────────────────────────────
constexpr uint32_t ACK_MAX_RETRIES         = 3;
constexpr uint32_t ACK_RETRY_BASE_MS       = 200;  // base for backoff: random(0, 200*2^attempt)
constexpr uint32_t ACK_TIMEOUT_MIN_MS      = 500;  // min timeout regardless of hop_start
constexpr uint32_t ACK_TIMEOUT_PER_HOP_MS  = 400;  // formula: max(500, 2*hop_start*400)
constexpr uint8_t  ACK_RETRY_QUEUE_SIZE    = 8;    // max in-flight ACKNOWLEDGED packets
constexpr uint8_t  ACK_HOP_START_FALLBACK  = 7;    // used when hop_start=0 (legacy device)

// ── Router duplicate-suppression cache ───────────────────────────────────────
constexpr uint32_t ROUTER_DUP_CACHE_SIZE    = 100;  // (source_id, packet_id) pairs
constexpr uint32_t ROUTER_DUP_CACHE_TTL_MS = 60000; // 60 s expiry per entry

// ── NODEINFO broadcast interval ───────────────────────────────────────────────
constexpr uint32_t NODEINFO_BROADCAST_INTERVAL_SECS = 10800; // 3 hours (§13.2)

// ── Display ───────────────────────────────────────────────────────────────────
constexpr uint32_t DISPLAY_MIN_REFRESH_MS = 60000; // 60 s min between full refreshes (§9.6)

} // namespace defaults
} // namespace mash
