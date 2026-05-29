// AirTAK — Airsoft Team Awareness Kit
// firmware/src/hal/Button.cpp — Debounced user button driver implementation
// Planning: §12.6, §7.1 | Roadmap: 1.7

#include "hal/Button.h"
#include "config/constants/Constants.h"
#include "config.h"
#include <Arduino.h>

namespace airtak {

static bool     s_last_state     = false;  // true = pressed (active low → inverted)
static uint32_t s_press_start_ms = 0;
static uint32_t s_last_release_ms = 0;
static uint8_t  s_press_count    = 0;

void Button::init() {
    pinMode(AIRTAK_PIN_USER_BUTTON, INPUT_PULLUP); // active low
}

ButtonGesture Button::poll() {
    bool pressed = (digitalRead(AIRTAK_PIN_USER_BUTTON) == LOW);
    uint32_t now = millis();

    if (pressed && !s_last_state) {
        // Button just pressed
        if ((now - s_last_release_ms) < BUTTON_DEBOUNCE_MS) {
            return ButtonGesture::NONE; // debounce
        }
        s_press_start_ms = now;
        s_press_count++;
        s_last_state = true;
    } else if (!pressed && s_last_state) {
        // Button just released
        uint32_t held = now - s_press_start_ms;
        s_last_release_ms = now;
        s_last_state = false;

        if (held >= BUTTON_LONG_PRESS_MS) {
            s_press_count = 0;
            return ButtonGesture::LONG_PRESS;
        }

        // Check for double press after timeout
        if (s_press_count >= 2 && (now - s_last_release_ms) < BUTTON_DOUBLE_PRESS_MS) {
            s_press_count = 0;
            return ButtonGesture::DOUBLE_PRESS;
        }

        return ButtonGesture::SHORT_PRESS;
    }

    // Reset press count if double-press window expired
    if (s_press_count > 0 && (now - s_last_release_ms) > BUTTON_DOUBLE_PRESS_MS && !pressed) {
        s_press_count = 0;
    }

    return ButtonGesture::NONE;
}

uint32_t Button::heldMs() {
    if (!s_last_state) return 0;
    return millis() - s_press_start_ms;
}

} // namespace airtak
