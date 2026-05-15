// MASH — Mesh Airsoft Situational Hub
// firmware/src/game/PlayerState.cpp — Own player state machine implementation
// Planning: §12.1, §12.6 | Roadmap: 5.3, 5.4

#include "game/PlayerState.h"
#include "mesh/Router.h"
#include "mesh/Portnums.h"
#include "config/Defaults.h"
#include "hal/Display.h"
#include <Arduino.h>

namespace mash {

static PlayerStatus s_status            = PlayerStatus::ACTIVE;
static uint32_t     s_respawn_seconds   = 0;
static uint32_t     s_hit_timestamp_ms  = 0;

constexpr uint32_t HIT_CANCEL_WINDOW_MS = 3000; // 3 s accidental press protection (§12.6)

void PlayerState::init() {
    s_status          = PlayerStatus::ACTIVE;
    s_respawn_seconds = 0;
}

void PlayerState::reportHit(QueueHandle_t tx_queue) {
    if (s_status != PlayerStatus::ACTIVE) return;
    s_status          = PlayerStatus::HIT;
    s_hit_timestamp_ms = millis();
    Display::markDirty(DISPLAY_DIRTY_GAME);
    // TODO Phase 5.3: Serialize PlayerStatePacket with status=HIT, is_state_transition=true.
    // Send via Router with ACKNOWLEDGED delivery and hop_limit from defaults.
    (void)tx_queue;
}

void PlayerState::cancelHit(QueueHandle_t tx_queue) {
    if (s_status != PlayerStatus::HIT) return;
    if (!isCancellable()) return;
    s_status = PlayerStatus::ACTIVE;
    Display::markDirty(DISPLAY_DIRTY_GAME);
    // TODO Phase 5.3: Broadcast ACTIVE state change.
    (void)tx_queue;
}

void PlayerState::tick(QueueHandle_t tx_queue) {
    if (s_status == PlayerStatus::HIT) {
        // After cancel window expires, move to RESPAWNING
        if (!isCancellable()) {
            s_status          = PlayerStatus::RESPAWNING;
            s_respawn_seconds = defaults::RESPAWN_TIMER_SECS;
            Display::markDirty(DISPLAY_DIRTY_GAME);
            // TODO Phase 5.3: Broadcast RESPAWNING state change.
        }
    }
    if (s_status == PlayerStatus::RESPAWNING && s_respawn_seconds > 0) {
        s_respawn_seconds--;
        Display::markDirty(DISPLAY_DIRTY_GAME);
        if (s_respawn_seconds == 0) {
            s_status = PlayerStatus::ACTIVE;
            // TODO Phase 5.3: Broadcast ACTIVE state change.
        }
    }
    (void)tx_queue;
}

void PlayerState::setRespawnTimerSeconds(uint32_t seconds) {
    s_respawn_seconds = seconds;
}

void PlayerState::eliminate(QueueHandle_t tx_queue) {
    s_status = PlayerStatus::ELIMINATED;
    Display::markDirty(DISPLAY_DIRTY_GAME);
    // TODO Phase 5.4: Broadcast ELIMINATED state change.
    (void)tx_queue;
}

PlayerStatus PlayerState::status()                { return s_status;          }
uint32_t     PlayerState::respawnSecondsRemaining(){ return s_respawn_seconds; }

bool PlayerState::isCancellable() {
    return s_status == PlayerStatus::HIT &&
           (millis() - s_hit_timestamp_ms) < HIT_CANCEL_WINDOW_MS;
}

} // namespace mash
