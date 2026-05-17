// AirTAK — Airsoft Team Awareness Kit
// firmware/src/game/PlayerState.h — Own player state
// Planning: §12.4, §12.6 | Roadmap: 5.2
//
// Simplified player status: ACTIVE/INACTIVE base + optional HIT/RESPAWNING/ELIMINATED.
// Manual toggle only — no automatic transitions, no respawn timers, no admin overrides.
// HIT has a 3 s cancel window for accidental press protection (§12.6).
// Extended-status players use 5-min position heartbeat (§8.7).

#pragma once

#include <cstdint>
#include <FreeRTOS.h>
#include <queue.h>

namespace airtak {

enum class PlayerStatus : uint8_t {
    ACTIVE       = 0,
    INACTIVE     = 1,
    HIT          = 2,   // optional, manually toggled
    RESPAWNING   = 3,   // optional, manually toggled
    ELIMINATED   = 4,   // optional, manually toggled
};

class PlayerState {
public:
    // Initialize player state to ACTIVE.
    static void init();

    // Toggle HIT status (ACTIVE↔HIT). Called from button handler on LONG_PRESS.
    // Starts the 3 s cancellation window.
    static void toggleHit(QueueHandle_t tx_queue);

    // Cancel HIT within the 3-second cancellation window (SHORT_PRESS).
    // Transitions HIT → ACTIVE.
    static void cancelHit(QueueHandle_t tx_queue);

    // Tick: handle HIT cancel window expiry. Called every second.
    static void tick(QueueHandle_t tx_queue);

    // Manually set status (called from BLE/admin path or device button).
    static void setStatus(PlayerStatus s, QueueHandle_t tx_queue);

    static PlayerStatus status();

    // True if within the 3-s accidental HIT cancellation window.
    static bool isCancellable();
};

} // namespace airtak
