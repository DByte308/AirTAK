// AirTAK — Airsoft Team Awareness Kit
// firmware/src/game/GameState.cpp — Game state mirror implementation
// Planning: §12.1–12.5 | Roadmap: 5.1–5.6

#include "game/GameState.h"
#include "hal/Display.h"
#include <cstring>
#include <Arduino.h>

namespace airtak {

static GamePhase   s_phase      = GamePhase::LOBBY;
static GameMode    s_mode       = GameMode::ELIMINATION;
static uint32_t    s_time_rem   = 0;
static uint32_t    s_last_update_ms = 0;
static ZoneMirror  s_zones[MAX_ZONES];
static uint8_t     s_zone_count = 0;
static TeamScore   s_teams[MAX_TEAMS];
static uint8_t     s_team_count = 0;

void GameState::init() {
    s_phase      = GamePhase::LOBBY;
    s_zone_count = 0;
    s_team_count = 0;
    s_time_rem   = 0;
    memset(s_zones, 0, sizeof(s_zones));
    memset(s_teams, 0, sizeof(s_teams));
}

void GameState::onGameStatePacket(const uint8_t* pb_data, size_t pb_len) {
    // TODO Phase 5.1: Decode nanopb GameStatePacket.
    // Update s_phase, s_mode, s_time_rem, team scores.
    // Notify display of game state change.
    Display::markDirty(DISPLAY_DIRTY_GAME);
    (void)pb_data; (void)pb_len;
    s_last_update_ms = millis();
}

void GameState::onZonePacket(const uint8_t* pb_data, size_t pb_len) {
    // TODO Phase 5.2: Decode nanopb ZonePacket. Update or insert zone in s_zones[].
    Display::markDirty(DISPLAY_DIRTY_GAME);
    (void)pb_data; (void)pb_len;
}

void GameState::onObjectivePacket(const uint8_t* pb_data, size_t pb_len) {
    // TODO Phase 5.3: Decode nanopb ObjectivePacket. Update objective state.
    Display::markDirty(DISPLAY_DIRTY_GAME);
    (void)pb_data; (void)pb_len;
}

GamePhase GameState::phase()               { return s_phase;      }
GameMode  GameState::mode()                { return s_mode;       }
uint32_t  GameState::timeRemainingSeconds(){ return s_time_rem;   }
uint8_t   GameState::zoneCount()           { return s_zone_count; }
uint8_t   GameState::teamCount()           { return s_team_count; }

const ZoneMirror* GameState::zone(uint8_t idx) {
    if (idx >= s_zone_count) return nullptr;
    return &s_zones[idx];
}

const TeamScore* GameState::teamScore(uint8_t idx) {
    if (idx >= s_team_count) return nullptr;
    return &s_teams[idx];
}

} // namespace airtak
