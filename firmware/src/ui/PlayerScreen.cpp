// MASH — Mesh Airsoft Situational Hub
// firmware/src/ui/PlayerScreen.cpp — Player status screen renderer
// Planning: §9.6 | Roadmap: 1.4
//
// Renders team name, player count, and the local player's status.
// When HIT: displays "HIT — hold to confirm / press to cancel" within cancellation window.
// When RESPAWNING: displays a countdown timer in large text.

#include "ui/Screens.h"
#include "game/PlayerState.h"
#include "game/GameState.h"
#include <Arduino.h>

namespace mash {
namespace ui {

void renderPlayerScreen() {
    // Header
    // TODO Phase 1.4: drawText("PLAYERS", header row);

    // Own status
    PlayerStatus own_status = PlayerState::status();
    switch (own_status) {
        case PlayerStatus::ACTIVE:
            // TODO: drawText("STATUS: ACTIVE", content row 1);
            break;
        case PlayerStatus::HIT:
            if (PlayerState::isCancellable()) {
                // TODO: drawText("HIT! Press to cancel", content row 1);
            } else {
                // TODO: drawText("HIT — confirming...", content row 1);
            }
            break;
        case PlayerStatus::RESPAWNING: {
            uint32_t secs = PlayerState::respawnSecondsRemaining();
            // TODO: printf("RESPAWN IN: %02u:%02u", secs / 60, secs % 60);
            (void)secs;
            break;
        }
        case PlayerStatus::ELIMINATED:
            // TODO: drawText("ELIMINATED", content row 1);
            break;
        default:
            break;
    }

    // Game phase
    GamePhase phase = GameState::phase();
    // TODO: drawText(phase == GamePhase::ACTIVE ? "GAME: ACTIVE" : "GAME: LOBBY", content row 2);
    (void)phase;

    // Team scores (first 2 teams if available)
    for (uint8_t t = 0; t < GameState::teamCount() && t < 2; t++) {
        const TeamScore* ts = GameState::teamScore(t);
        if (ts) {
            // TODO: printf("%s: %u", ts->team_name, ts->score);
        }
    }
}

} // namespace ui
} // namespace mash
