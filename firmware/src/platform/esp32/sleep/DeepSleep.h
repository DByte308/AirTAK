// AirTAK — Airsoft Team Awareness Kit
// firmware/src/platform/esp32/sleep/DeepSleep.h — ESP32 deep sleep / power-off
// Planning: §12.7 | Roadmap: 2.9
//
// ESP32-S3 deep-sleep entry with GPIO (button) and timer wake sources.
// Before entering deep sleep, shuts down all peripherals (radio, display,
// BLE, Vext power rail) to minimise quiescent current.

#pragma once

#include <cstdint>

namespace airtak {
namespace sleep {

/// What caused the last wake from deep sleep.
enum class WakeCause : uint8_t {
    NOT_DEEP_SLEEP = 0,  // cold boot or reset, not a deep-sleep wake
    TIMER          = 1,  // esp_sleep_enable_timer_wakeup
    BUTTON         = 2,  // esp_sleep_enable_ext1_wakeup (user button)
    UNKNOWN        = 3,
};

/// Call once at boot (before FreeRTOS starts) to detect and release
/// deep-sleep wake state.  Must be called early because RTC GPIOs and
/// memory are held until the wake cause is read.
void initDeepSleep();

/// Returns what caused the most recent wake.
WakeCause wakeCause();

/// Enter deep sleep.  Powers down all peripherals, then calls
/// esp_deep_sleep_start().  If [sleepMs] is 0 the chip stays asleep until
/// the user presses the button (indefinite / off); otherwise it wakes
/// after the given interval.
/// This function never returns (the chip resets on wake).
[[noreturn]] void enterDeepSleep(uint64_t sleepMs);

} // namespace sleep
} // namespace airtak
