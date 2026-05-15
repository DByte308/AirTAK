// AirTAK — Airsoft Team Awareness Kit
// firmware/src/game/GameState.h — Game state mirror
// Planning: §12.1–12.5 | Roadmap: 5.1–5.6
//
// Maintains the local node's authoritative mirror of:
//   - Game phase (LOBBY / ACTIVE / PAUSED / ENDED)
//   - Zone table (up to 16 zones)
//   - Objective states
//   - Team scores
//
// Updated by incoming AIRTAK_GAME, AIRTAK_ZONE, and AIRTAK_OBJECTIVE packets.
// Read by the display task and BLE DEVICE_STATUS notifications.

#pragma once

#include <cstdint>
#include <cstddef>

namespace airtak {

constexpr uint8_t MAX_ZONES      = 16;
constexpr uint8_t MAX_OBJECTIVES = 8;
constexpr uint8_t MAX_TEAMS      = 4;

enum class GamePhase : uint8_t {
    IDLE    = 0,
    LOBBY   = 1,
    ACTIVE  = 2,
    PAUSED  = 3,
    ENDED   = 4,
};

enum class GameMode : uint8_t {
    DEATHMATCH   = 0,
    DOMINATION   = 1,
    CAPTURE_FLAG = 2,
    ELIMINATION  = 3,
    CUSTOM       = 15,
};

struct ZoneMirror {
    uint8_t  zone_id;
    uint8_t  zone_type;         // ZoneType from proto/airtak/game.proto
    int32_t  lat_e7;            // latitude × 10^7
    int32_t  lon_e7;            // longitude × 10^7
    uint32_t radius_m;
    char     name[17];
    uint8_t  controlled_by;     // team index, 0xFF = contested
    bool     active;
};

struct TeamScore {
    uint8_t team_index;
    char    team_name[17];
    uint32_t score;
    uint8_t  player_count;
};

class GameState {
public:
    // Phase 5.1: Initialize empty game state (IDLE phase).
    static void init();

    // Phase 5.1: Handle incoming GameStatePacket (deserialized from AIRTAK_GAME portnum).
    static void onGameStatePacket(const uint8_t* pb_data, size_t pb_len);

    // Phase 5.2: Handle incoming ZonePacket (AIRTAK_ZONE portnum).
    static void onZonePacket(const uint8_t* pb_data, size_t pb_len);

    // Phase 5.3: Handle incoming ObjectivePacket (AIRTAK_OBJECTIVE portnum).
    static void onObjectivePacket(const uint8_t* pb_data, size_t pb_len);

    // Accessors
    static GamePhase phase();
    static GameMode  mode();
    static uint32_t  timeRemainingSeconds();
    static const ZoneMirror* zone(uint8_t idx);
    static uint8_t           zoneCount();
    static const TeamScore*  teamScore(uint8_t idx);
    static uint8_t           teamCount();
};

} // namespace airtak
