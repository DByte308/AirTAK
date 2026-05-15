// MASH — Mesh Airsoft Situational Hub
// firmware/src/hal/Battery.cpp — ADC battery voltage monitor implementation
// Planning: §7.1, §7.2 | Roadmap: 1.7

#include "hal/Battery.h"
#include "hal/Display.h"
#include "firmware/include/config.h"
#include <Arduino.h>

namespace mash {

static float    s_level        = 0.0f;
static bool     s_is_charging  = false;
static uint32_t s_voltage_mv   = 0;

void Battery::init() {
    // Configure ADC input for VBAT_SENSE (P0.04 / AIN2)
    // Configure CHG_STAT as input with pull-up (P0.05, active low = charging)
    pinMode(PIN_VBAT_SENSE, INPUT);
    pinMode(PIN_CHG_STAT,   INPUT_PULLUP);
}

void Battery::sample(EventGroupHandle_t system_event_group) {
    // TODO Phase 1.7: Read ADC voltage on PIN_VBAT_SENSE.
    // The T-Echo Plus uses a voltage divider; apply the correct scaling factor.
    // Map raw millivolts to 0.0–1.0 using a LiPo discharge curve approximation:
    //   4200 mV = 1.0 (full)  |  3700 mV = 0.5  |  3200 mV = 0.0 (empty)

    uint32_t raw_adc = analogRead(PIN_VBAT_SENSE);
    // Placeholder: assume 3.3V ADC reference, 12-bit, 2× voltage divider
    s_voltage_mv = (raw_adc * 3300 * 2) / 4096;

    // Clamp and normalise
    if (s_voltage_mv >= 4200) s_level = 1.0f;
    else if (s_voltage_mv <= 3200) s_level = 0.0f;
    else s_level = (float)(s_voltage_mv - 3200) / (4200 - 3200);

    // CHG_STAT: active low = charging (typical TP4054 behavior)
    s_is_charging = (digitalRead(PIN_CHG_STAT) == LOW);

    // Notify display and BLE status
    Display::markDirty(DISPLAY_DIRTY_BATTERY);
    if (system_event_group) {
        xEventGroupSetBits(system_event_group, BATTERY_EVENT_UPDATED);
    }
}

float    Battery::level()             { return s_level;       }
bool     Battery::isCharging()        { return s_is_charging; }
uint32_t Battery::voltageMillivolts() { return s_voltage_mv;  }

} // namespace mash
