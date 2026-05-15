// AirTAK — Airsoft Team Awareness Kit
// firmware/src/game/PlayerState.h — Own player state machine
// Planning: §12.1, §12.6 | Roadmap: 5.3, 5.4
//
// State machine for the local player:
//   ACTIVE → HIT (button LONG_PRESS) → RESPAWNING (countdown) → ACTIVE
//   HIT may be cancelled by SHORT_PRESS within 3 s (accidental press protection)
//   ELIMINATED: final state, no respawn (game mode dependent)
//
// Broadcasts AIRTAK_PLAYER (PlayerStatePacket) with is_state_transition=true on every
// state change (§12.6). Uses ACKNOWLEDGED delivery (§8.9).
// Uses 5-minute position heartbeat while in HIT/RESPAWNING/ELIMINATED (§8.7).

#pragma once

#include <cstdint>
#include <FreeRTOS.h>
#include <queue.h>

namespace airtak {

enum class PlayerStatus : uint8_t {
    ACTIVE       = 0,
    HIT          = 1,
    RESPAWNING   = 2,
    ELIMINATED   = 3,
    SPECTATING   = 4,   // matches PLAYER_STATUS_SPECTATING in game.proto
};

class PlayerState {
public:
    // Phase 5.3: Initialize player state to ACTIVE.
    static void init();

    // Phase 5.3: Called from button handler when a LONG_PRESS is detected.
    // Transitions ACTIVE → HIT and starts the cancellation window (3 s).
    static void reportHit(QueueHandle_t tx_queue);

    // Phase 5.3: Called within the 3-second cancellation window (SHORT_PRESS).
    // Transitions HIT → ACTIVE and broadcasts the state change.
    static void cancelHit(QueueHandle_t tx_queue);

    // Phase 5.3: Tick the respawn countdown. Called every second from the BLE/game task.
    static void tick(QueueHandle_t tx_queue);

    // Phase 5.4: Called by admin to set respawn time or eliminate a player.
    static void setRespawnTimerSeconds(uint32_t seconds);
    static void eliminate(QueueHandle_t tx_queue);

    static PlayerStatus status();
    static uint32_t     respawnSecondsRemaining();

    // True if within the 3-s accidental HIT cancellation window.
    static bool isCancellable();
};

} // namespace airtak
