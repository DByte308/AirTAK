// AirTAK — Airsoft Team Awareness Kit
// firmware/src/config/constants/ButtonConstants.h — Button debounce and gesture timing
// Planning: §12.6
//
// Timing constants for button debounce, long press detection, and double press
// detection. Used by hal/Button.cpp for gesture classification.

#pragma once
#include <cstdint>

namespace airtak {

// ── Button Timing (§12.6) ──────────────────────────────────────────────────
constexpr uint32_t BUTTON_DEBOUNCE_MS        = 50;     // Ignore transitions < 50 ms
constexpr uint32_t BUTTON_LONG_PRESS_MS      = 3000;   // ≥ 3 s = long press
constexpr uint32_t BUTTON_DOUBLE_PRESS_MS    = 400;    // Max gap between presses for double

// ── Button Event Bit ───────────────────────────────────────────────────────
// Posted to system_event_group when a gesture is detected.
// Defined as uint32_t for portability (EventBits_t is uint32_t on all platforms).
constexpr uint32_t BUTTON_EVENT_GESTURE      = (1 << 13);

} // namespace airtak
