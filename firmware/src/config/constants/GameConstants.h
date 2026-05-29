// AirTAK — Airsoft Team Awareness Kit
// Game Logic Constants

#pragma once
#include <cstdint>

namespace airtak {

// ── Player State ───────────────────────────────────────────────────────────
// Hit cancel window: short press within this many ms after HIT cancels it
constexpr uint32_t PLAYER_HIT_CANCEL_WINDOW_MS = 3000;   // 3 seconds

// ── Default Player Color ───────────────────────────────────────────────────
constexpr uint32_t GAME_DEFAULT_PLAYER_COLOR   = 0x2196F3; // Material Blue

// ── Game Callsign Max Length ──────────────────────────────────────────────
constexpr size_t   GAME_CALLSIGN_MAX_BYTES     = 17;    // Max callsign bytes (from ConfigManager)

} // namespace airtak
