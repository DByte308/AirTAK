// AirTAK — Airsoft Team Awareness Kit
// firmware/src/config/constants/GameConstants.h — Game/player state constants
// Planning: §12.5, §12.6

#pragma once
#include <cstdint>

namespace airtak {

// ── Player State ───────────────────────────────────────────────────────────
// Hit cancel window: short press within this many ms after HIT cancels it
constexpr uint32_t PLAYER_HIT_CANCEL_WINDOW_MS = 3000;   // 3 seconds

// ── Player Status Enumeration (§12.5) ─────────────────────────────────────
enum class PlayerStatus : uint8_t {
    ACTIVE      = 0,   // In-game, broadcasting position
    INACTIVE    = 1,   // Not in game, silent
    HIT         = 2,   // Marked as hit — sends heartbeat at reduced rate
    RESPAWNING  = 3,   // Returning to spawn — sends heartbeat
    ELIMINATED  = 4,   // Permanently out (per game rules)
};

// ── Default Player Color ───────────────────────────────────────────────────
constexpr uint32_t GAME_DEFAULT_PLAYER_COLOR   = 0x2196F3; // Material Blue

// ── Game Callsign Max Length ──────────────────────────────────────────────
constexpr size_t   GAME_CALLSIGN_MAX_BYTES     = 17;    // Max callsign bytes

// ── Default Config Booleans ────────────────────────────────────────────────
constexpr bool     GAME_HIT_BUTTON_ENABLED_DEF = false;  // Default: hit button disabled

} // namespace airtak
