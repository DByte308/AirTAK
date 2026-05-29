// AirTAK — Airsoft Team Awareness Kit
// firmware/src/config/Defaults.h — Factory default values for all config groups
// Planning: §9.4.1, §8.1, §8.7, §14.2 | Roadmap: 1.6, 2.9
//
// These values are loaded when no config file exists on LittleFS (first boot).
// All configurable values that have a runtime override live here — not in config.h.
// Constants that are truly fixed hardware constraints live in firmware/include/config.h.

#pragma once

#include <cstdint>
#include "config/constants/Constants.h"

namespace airtak {
namespace defaults {

// ── DeviceConfig ──────────────────────────────────────────────────────────────
constexpr const char* DEVICE_LONG_NAME  = "AirTAK Node";
constexpr const char* DEVICE_SHORT_NAME = "nod1";

// ── LoraConfig ────────────────────────────────────────────────────────────────
// Region: 0=EU_868, 1=US_915. Set at first-time setup via Settings > LoRa.
constexpr uint8_t  LORA_REGION       = LORA_REGION_DEFAULT;       // Canonical: RadioConstants.h
constexpr uint8_t  LORA_TX_POWER_DBM = LORA_TX_POWER_DBM_DEFAULT; // Canonical: RadioConstants.h

// PHY Profile defaults (indexes 0–4). See §8.1 for SF/BW/CR semantics.
// All five profiles share sync word 0x2B, preamble 16, explicit header, CRC on, IQ normal.
struct PhyProfileDefault {
    const char* name;
    uint8_t     spreading_factor;
    uint32_t    bandwidth_hz;
    uint8_t     coding_rate;   // 5=4/5  6=4/6  7=4/7  8=4/8
};

constexpr PhyProfileDefault PHY_PROFILES[PHY_PROFILE_COUNT] = {
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

// Per-PHY-profile SNR maxima used by the relay delay formula (§8.7).
// SNR_max varies with spreading factor — higher SF tolerates more noise.
// These are conservative estimates for SX1262 at 250 kHz BW.
constexpr float PHY_SNR_MAX[PHY_PROFILE_COUNT] = {
    10.0f,   // [0] SF11 — Long/Mesh
     6.0f,   // [1] SF7  — Short/Speed
    12.0f,   // [2] SF12 — Long/Reliable
     9.0f,   // [3] SF10 — Medium/Slow
    10.0f,   // [4] User Defined — fall back to Long/Mesh value
};

// ── ChannelConfig — 8 slots (§8.5) ───────────────────────────────────────────
// PSK must be set by user at first-time setup. Empty = no encryption (insecure).
constexpr uint8_t CHANNEL_COUNT = MAX_CHANNEL_SLOTS; // Canonical: MeshConstants.h

struct ChannelSlotDefault {
    uint8_t     index;
    const char* name;
    uint8_t     role;                // 0=DISABLED 1=PRIMARY 2=SECONDARY
    bool        uplink_enabled;
    bool        downlink_enabled;
    uint8_t     position_precision;  // 0–32
    uint8_t     phy_profile_index;
};

constexpr ChannelSlotDefault CHANNEL_SLOTS[MAX_CHANNEL_SLOTS] = {
    // Slot 0: PRIMARY — Meshtastic-compatible + AirTAK game traffic
    { 0, "AirTAK",   airtak::CHANNEL_ROLE_PRIMARY, true,  true,  airtak::POSITION_GPS_PRECISION_BITS, airtak::PHY_PROFILE_DEFAULT },
    // Slot 1: SECONDARY — AirTAK tactical traffic (separate PSK)
    { 1, "Tactical",   airtak::CHANNEL_ROLE_SECONDARY, true,  true,  airtak::POSITION_GPS_PRECISION_BITS, airtak::PHY_PROFILE_DEFAULT },
    // Slot 2: DISABLED — pre-configured for Phase 7 PTT audio (Profile 1: Short/Speed)
    { 2, "Audio", airtak::CHANNEL_ROLE_DISABLED, true,  true,  airtak::POSITION_GPS_PRECISION_BITS, 1 },
    // Slots 3–7: DISABLED
    { 3, "Ch 3",  airtak::CHANNEL_ROLE_DISABLED, false, false, airtak::POSITION_GPS_PRECISION_BITS, airtak::PHY_PROFILE_DEFAULT },
    { 4, "Ch 4",  airtak::CHANNEL_ROLE_DISABLED, false, false, airtak::POSITION_GPS_PRECISION_BITS, airtak::PHY_PROFILE_DEFAULT },
    { 5, "Ch 5",  airtak::CHANNEL_ROLE_DISABLED, false, false, airtak::POSITION_GPS_PRECISION_BITS, airtak::PHY_PROFILE_DEFAULT },
    { 6, "Ch 6",  airtak::CHANNEL_ROLE_DISABLED, false, false, airtak::POSITION_GPS_PRECISION_BITS, airtak::PHY_PROFILE_DEFAULT },
    { 7, "Ch 7",  airtak::CHANNEL_ROLE_DISABLED, false, false, airtak::POSITION_GPS_PRECISION_BITS, airtak::PHY_PROFILE_DEFAULT },
};

// ── PositionConfig — Smart beaconing (§8.7) ───────────────────────────────────
// Canonical position values: MeshConstants.h
constexpr uint32_t POSITION_BROADCAST_INTERVAL_SECS = airtak::POSITION_BROADCAST_INTERVAL_SECS;
constexpr uint32_t POSITION_GPS_PRECISION_BITS       = airtak::POSITION_GPS_PRECISION_BITS;
constexpr bool     POSITION_SMART_BROADCAST          = airtak::POSITION_SMART_BROADCAST_DEF;
constexpr uint32_t POSITION_MOVEMENT_THRESHOLD_M     = airtak::POSITION_MOVEMENT_THRESHOLD_M;
constexpr uint32_t POSITION_MIN_TX_INTERVAL_SECS     = airtak::POSITION_MIN_TX_INTERVAL_SECS;
constexpr uint32_t POSITION_HIT_HEARTBEAT_SECS       = airtak::POSITION_HIT_HEARTBEAT_SECS;
constexpr uint8_t  POSITION_GPS_SOURCE               = airtak::POSITION_GPS_SOURCE_DEF;
constexpr uint8_t  POSITION_CHANNEL_INDEX             = airtak::POSITION_CHANNEL_INDEX_DEF;

// ── Per-packet-type default hop limits (§8.7, updated v1.5) ────────────────────
// All types use HOP_LIMIT_DEFAULT=7 except AIRTAK_AUDIO=HOP_LIMIT_AUDIO=1.
// Rationale: hop_limit does not create extra relay traffic — relay count is bounded
// by mesh topology. Restricting hop_limit only prevents delivery to far-edge nodes.
// Airtime is controlled by smart beaconing TX interval, not hop_limit fields.
// POSITION_APP can be overridden via PositionConfig.position_hop_limit.
// Canonical definitions: HOP_LIMIT_DEFAULT=7, HOP_LIMIT_AUDIO=1 (MeshConstants.h)
constexpr uint8_t HOP_LIMIT_TEXT_MESSAGE     = HOP_LIMIT_DEFAULT;
constexpr uint8_t HOP_LIMIT_POSITION         = HOP_LIMIT_DEFAULT;
constexpr uint8_t HOP_LIMIT_NODEINFO         = HOP_LIMIT_DEFAULT;
constexpr uint8_t HOP_LIMIT_TELEMETRY        = HOP_LIMIT_DEFAULT;
constexpr uint8_t HOP_LIMIT_TRACEROUTE       = HOP_LIMIT_DEFAULT;
constexpr uint8_t HOP_LIMIT_NEIGHBORINFO     = HOP_LIMIT_DEFAULT;
constexpr uint8_t HOP_LIMIT_AIRTAK_PLAYER      = HOP_LIMIT_DEFAULT;
constexpr uint8_t HOP_LIMIT_AIRTAK_TACTICAL     = HOP_LIMIT_DEFAULT;
constexpr uint8_t HOP_LIMIT_AIRTAK_MODERATION   = HOP_LIMIT_DEFAULT;
constexpr uint8_t HOP_LIMIT_AIRTAK_ACK         = HOP_LIMIT_DEFAULT; // Fallback: ACK built dynamically in
                                                     // processIncoming() uses hop_start of the original
                                                     // packet; this is the safety default (§8.9)
constexpr uint8_t HOP_LIMIT_POSITION_REQUEST = HOP_LIMIT_DEFAULT;
constexpr uint8_t HOP_LIMIT_AIRTAK_AUDIO       = HOP_LIMIT_AUDIO;  // MUST never relay audio (§12.7)
constexpr uint8_t HOP_LIMIT_AIRTAK_SYNC_REQUEST = HOP_LIMIT_DEFAULT;
constexpr uint8_t HOP_LIMIT_OVERLAY_ALIGNMENT   = HOP_LIMIT_DEFAULT;
constexpr uint8_t HOP_LIMIT_MARKER_SNAPSHOT     = HOP_LIMIT_DEFAULT;

// ── PowerConfig ───────────────────────────────────────────────────────────────
constexpr bool     POWER_GPS_DUTY_CYCLE    = false;  // disabled until Phase 6
constexpr uint32_t POWER_GPS_ON_SECS       = airtak::POWER_GPS_ON_SECS_DEF;
constexpr uint32_t POWER_GPS_OFF_SECS      = airtak::POWER_GPS_OFF_SECS_DEF;
constexpr uint32_t POWER_DISPLAY_OFF_SECS  = DISPLAY_OFF_SECS_DEFAULT;    // Canonical: DisplayConstants.h
constexpr uint32_t POWER_BRIGHTNESS_PCT    = DISPLAY_BRIGHTNESS_PCT_DEF;  // Canonical: DisplayConstants.h

// ── GameConfig ────────────────────────────────────────────────────────────────
constexpr const char* GAME_CALLSIGN           = "Player";
constexpr const char* GAME_FIRETEAM_ID        = "";    // free-text, optional
constexpr const char* GAME_SQUAD_ID           = "";    // free-text, optional
constexpr const char* GAME_PLATOON_ID         = "";    // free-text, optional
constexpr const char* GAME_COMPANY_ID         = "";    // free-text, optional
constexpr uint32_t    GAME_PLAYER_COLOR_RGB   = GAME_DEFAULT_PLAYER_COLOR; // Canonical: GameConstants.h
constexpr bool        GAME_HIT_BUTTON_ENABLED = airtak::GAME_HIT_BUTTON_ENABLED_DEF;

// ── SecurityConfig ────────────────────────────────────────────────────────────
constexpr uint32_t SECURITY_MAX_BONDED_DEVICES = airtak::SECURITY_MAX_BONDED_DEF;

// ── AudioConfig (Phase 7) ─────────────────────────────────────────────────────
constexpr uint32_t AUDIO_CHANNEL_SLOT  = AUDIO_DEFAULT_CHANNEL_SLOT; // Canonical: AudioConstants.h
constexpr uint32_t AUDIO_CODEC2_BPS    = AUDIO_DEFAULT_CODEC2_BPS;   // Canonical: AudioConstants.h
constexpr bool     AUDIO_PTT_ENABLED   = false; // disabled until user opts in
constexpr uint32_t AUDIO_FRAME_COUNT   = AUDIO_DEFAULT_FRAME_COUNT;  // Canonical: AudioConstants.h

// ── Application-layer reliability (§8.9) ────────────────────────────────────
// Canonical: MeshConstants.h
constexpr uint32_t ACK_MAX_RETRIES         = airtak::ACK_MAX_RETRIES;
constexpr uint32_t ACK_RETRY_BASE_MS       = airtak::ACK_RETRY_BASE_MS;
constexpr uint32_t ACK_TIMEOUT_MIN_MS      = airtak::ACK_TIMEOUT_MIN_MS;
constexpr uint32_t ACK_TIMEOUT_PER_HOP_MS  = airtak::ACK_TIMEOUT_PER_HOP_MS;
constexpr uint8_t  ACK_RETRY_QUEUE_SIZE    = airtak::ACK_RETRY_QUEUE_SIZE;
constexpr uint8_t  ACK_HOP_START_FALLBACK  = airtak::ACK_HOP_START_FALLBACK;

// ── Router duplicate-suppression cache ───────────────────────────────────────
// Canonical: MeshConstants.h
constexpr uint32_t ROUTER_DUP_CACHE_SIZE   = airtak::ROUTER_DUP_CACHE_SIZE;
constexpr uint32_t ROUTER_DUP_CACHE_TTL_MS = airtak::ROUTER_DUP_CACHE_TTL_MS;

// ── NODEINFO broadcast interval ───────────────────────────────────────────────
// Meshtastic default is 900 s (15 min); AirTAK was 10800 s (3 h) which is far
// too long — the node appears stale in the Meshtastic app's node list.
// Canonical: MeshConstants.h
constexpr uint32_t NODEINFO_BROADCAST_INTERVAL_SECS = airtak::NODEINFO_BROADCAST_INTERVAL_SECS;

// ── Startup announce burst ────────────────────────────────────────────────────
// After the initial explicit NODEINFO at boot, backdate the periodic timer so
// tick() fires a follow-up rebroadcast after this many milliseconds.  This
// ensures the Meshtastic app discovers the device quickly even if it connected
// after the very first broadcast.
// Canonical: MeshConstants.h
constexpr uint32_t STARTUP_ANNOUNCE_DELAY_MS = airtak::STARTUP_ANNOUNCE_DELAY_MS;

// ── Telemetry broadcast interval ──────────────────────────────────────────────
// Canonical: MeshConstants.h
constexpr uint32_t TELEMETRY_BROADCAST_INTERVAL_SECS = airtak::TELEMETRY_BROADCAST_INTERVAL_SECS;

// ── Display ───────────────────────────────────────────────────────────────────
// Canonical: DisplayConstants.h
constexpr uint32_t DISPLAY_MIN_REFRESH_MS = airtak::DISPLAY_MIN_REFRESH_MS;

// ── Duty-cycle-aware relay suppression (§8.7.1, Roadmap 6.10) ──────────────────
// Thresholds as percentage of the per-sub-band regulatory limit.
// All values are in Defaults.h only — not user-configurable via BLE.
// Canonical: DcConstants.h
constexpr uint8_t  DC_CAUTION_THRESHOLD_PCT  = airtak::DC_CAUTION_THRESHOLD_PCT;
constexpr uint8_t  DC_WARNING_THRESHOLD_PCT  = airtak::DC_WARNING_THRESHOLD_PCT;
constexpr uint8_t  DC_CRITICAL_THRESHOLD_PCT = airtak::DC_CRITICAL_THRESHOLD_PCT;
constexpr uint8_t  DC_TX_LOG_SIZE            = airtak::DC_TX_LOG_SIZE;
constexpr uint32_t DC_LIMIT_EU868_G1_MS_PER_HOUR = airtak::DC_LIMIT_EU868_G1_MS_PER_HOUR;

} // namespace defaults
} // namespace airtak
