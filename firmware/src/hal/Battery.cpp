// MASH — Mesh Airsoft Situational Hub
// firmware/src/hal/Battery.cpp — ADC battery voltage monitor implementation
// Planning: §7.1, §7.2 | Roadmap: 1.7

#include "hal/Battery.h"
#include "hal/Display.h"
#include "config.h"
#include <Arduino.h>

namespace mash {

static float    s_level        = 0.0f;
static bool     s_is_charging  = false;
static uint32_t s_voltage_mv   = 0;

constexpr uint32_t ADC_REFERENCE_MV = 3000;
constexpr uint32_t ADC_MAX_COUNTS   = 4095;
constexpr uint32_t VBAT_DIVIDER_NUM = 2;
constexpr uint32_t VBAT_DIVIDER_DEN = 1;

void Battery::init() {
    // Configure ADC input for VBAT_SENSE (P0.04 / AIN2)
    analogReadResolution(12);
    pinMode(MASH_PIN_VBAT_SENSE, INPUT);
#if MASH_PIN_CHG_STAT >= 0
    pinMode(MASH_PIN_CHG_STAT,   INPUT_PULLUP);
#endif
}

void Battery::sample(EventGroupHandle_t system_event_group) {
    uint32_t raw_adc = analogRead(MASH_PIN_VBAT_SENSE);
    s_voltage_mv = (raw_adc * ADC_REFERENCE_MV * VBAT_DIVIDER_NUM)
                 / (ADC_MAX_COUNTS * VBAT_DIVIDER_DEN);

    // Clamp and normalise
    if (s_voltage_mv >= 4200) s_level = 1.0f;
    else if (s_voltage_mv <= 3200) s_level = 0.0f;
    else s_level = (float)(s_voltage_mv - 3200) / (4200 - 3200);

    // CHG_STAT: active low = charging (typical TP4054 behavior)
#if MASH_PIN_CHG_STAT >= 0
    s_is_charging = (digitalRead(MASH_PIN_CHG_STAT) == LOW);
#else
    s_is_charging = false;
#endif

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
