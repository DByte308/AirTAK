// AirTAK — Airsoft Team Awareness Kit
// firmware/src/game/PlayerState.cpp — Own player state implementation
// Planning: §12.4, §12.6 | Roadmap: 5.2

#include "game/PlayerState.h"
#include "mesh/Router.h"
#include "mesh/Portnums.h"
#include "config/Defaults.h"
#include "hal/Display.h"
#include <Arduino.h>

namespace airtak {

static PlayerStatus s_status           = PlayerStatus::ACTIVE;
static uint32_t     s_hit_timestamp_ms = 0;
static bool         s_hit_button_enabled = defaults::GAME_HIT_BUTTON_ENABLED;

constexpr uint32_t HIT_CANCEL_WINDOW_MS = 3000; // 3 s accidental press protection (§12.6)

void PlayerState::init() {
    s_status           = PlayerStatus::ACTIVE;
    s_hit_timestamp_ms = 0;
    s_hit_button_enabled = defaults::GAME_HIT_BUTTON_ENABLED;
}

void PlayerState::toggleHit(QueueHandle_t tx_queue) {
    if (!s_hit_button_enabled) return;
    if (s_status == PlayerStatus::ACTIVE) {
        s_status           = PlayerStatus::HIT;
        s_hit_timestamp_ms = millis();
        Display::markDirty(DISPLAY_DIRTY_GAME);
        // TODO Phase 5.2: Serialize PlayerStatePacket with status=HIT, is_state_transition=true.
        // Send via Router with ACKNOWLEDGED delivery.
        (void)tx_queue;
    } else if (s_status == PlayerStatus::HIT) {
        // Second long press while already HIT toggles back to ACTIVE.
        s_status = PlayerStatus::ACTIVE;
        Display::markDirty(DISPLAY_DIRTY_GAME);
        (void)tx_queue;
    }
}

void PlayerState::cancelHit(QueueHandle_t tx_queue) {
    if (s_status != PlayerStatus::HIT) return;
    if (!isCancellable()) return;
    s_status = PlayerStatus::ACTIVE;
    Display::markDirty(DISPLAY_DIRTY_GAME);
    // TODO Phase 5.2: Broadcast ACTIVE state change.
    (void)tx_queue;
}

void PlayerState::tick(QueueHandle_t tx_queue) {
    // After HIT cancel window expires, the HIT status stands.
    // No automatic transition — player must manually toggle back to ACTIVE.
    // The cancel window expiry itself does not change state; it only
    // makes cancelHit() a no-op from this point forward.
    (void)tx_queue;
}

void PlayerState::setStatus(PlayerStatus s, QueueHandle_t tx_queue) {
    if (s == s_status) return;
    s_status = s;
    if (s == PlayerStatus::HIT) {
        s_hit_timestamp_ms = millis();
    }
    Display::markDirty(DISPLAY_DIRTY_GAME);
    // TODO Phase 5.2: Broadcast PlayerStatePacket with is_state_transition=true.
    (void)tx_queue;
}

PlayerStatus PlayerState::status() { return s_status; }

bool PlayerState::isCancellable() {
    return s_status == PlayerStatus::HIT &&
           (millis() - s_hit_timestamp_ms) < HIT_CANCEL_WINDOW_MS;
}

} // namespace airtak
