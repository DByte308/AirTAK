// AirTAK — Airsoft Team Awareness Kit
// firmware/src/hal/Button.h — Debounced user button driver
// Planning: §12.6, §7.1 | Roadmap: 1.7
//
// Detects short press, long press (≥ 3 s), and double press on USER button (P1.10, active low).
// Routes gestures to Display::nextScreen() and PlayerState::toggleHit() per §12.6.

#pragma once

#include <cstdint>
#include <FreeRTOS.h>
#include <event_groups.h>

namespace airtak {

enum class ButtonGesture : uint8_t {
    NONE         = 0,
    SHORT_PRESS  = 1,  // advance display screen; cancel HIT within 3 s window (§12.6)
    LONG_PRESS   = 2,  // ≥ 3 s: report HIT when ACTIVE (§12.6)
    DOUBLE_PRESS = 3,  // reserved for future hardware (§12.6)
};

class Button {
public:
    // Phase 1.7: Configure USER button GPIO with pull-up (active low).
    static void init();

    // Phase 1.7: Poll/interrupt-driven gesture detection. Called from event loop or task.
    // Returns the latest gesture (NONE if no new gesture since last call).
    static ButtonGesture poll();

    // Returns time in ms that the button has been continuously held.
    static uint32_t heldMs();
};

// Event bit for button gesture available
constexpr EventBits_t BUTTON_EVENT_GESTURE = (1 << 13);

} // namespace airtak
