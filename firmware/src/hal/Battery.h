// AirTAK — Airsoft Team Awareness Kit
// firmware/src/hal/Battery.h — ADC battery voltage monitor
// Planning: §7.1, §7.2 | Roadmap: 1.7
//
// Reads battery voltage via P0.04 (AIN2 ADC) and charge state via P0.05 (CHG_STAT).
// Reports battery level (0.0–1.0) and charging state to DeviceStatus (§10.4).

#pragma once

#include <cstdint>
#include <FreeRTOS.h>
#include <event_groups.h>

namespace airtak {

class Battery {
public:
    // Phase 1.7: Initialize ADC and charge-state GPIO.
    static void init();

    // Phase 1.7: Sample ADC and update state. Called from task_battery_monitor() every 60 s.
    // Updates the shared DeviceStatus fields and marks Display DIRTY_BATTERY.
    static void sample(EventGroupHandle_t system_event_group);

    // Returns battery level 0.0–1.0. Derived from raw ADC voltage vs. LiPo discharge curve.
    static float level();

    // Returns true if USB charging is detected (CHG_STAT pin low = charging).
    static bool isCharging();

    // Raw ADC voltage in millivolts (diagnostic use only).
    static uint32_t voltageMillivolts();
};

// Event bit for battery update notification
constexpr EventBits_t BATTERY_EVENT_UPDATED = (1 << 12);

} // namespace airtak
