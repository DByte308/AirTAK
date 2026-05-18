// AirTAK — Airsoft Team Awareness Kit
// firmware/src/hal/Battery.cpp — ADC battery voltage monitor implementation
// Planning: §7.1, §7.2 | Roadmap: 1.7

#include "hal/Battery.h"
#include "hal/Display.h"
#include "config.h"
#include <Arduino.h>

namespace airtak {

static float    s_level        = 0.0f;
static bool     s_is_charging  = false;
static uint32_t s_voltage_mv   = 0;

constexpr uint32_t ADC_REFERENCE_MV = 3300;
constexpr uint32_t ADC_MAX_COUNTS   = 4095;

// Per-board calibration override (default 1.0 = no adjustment).
// Some boards need a correction for trace resistance, diode/MOSFET drop,
// or ESP32 ADC non-linearity (e.g. T-Deck: 1.08, T-Echo: 1.0).
#ifndef AIRTAK_ADC_MULTIPLIER
#define AIRTAK_ADC_MULTIPLIER 1.0f
#endif

void Battery::init() {
    analogReadResolution(12);
#ifdef AIRTAK_PLATFORM_ESP32
    analogSetAttenuation(ADC_11db);
#endif
    pinMode(AIRTAK_PIN_VBAT_SENSE, INPUT);
#if AIRTAK_PIN_CHG_STAT >= 0
    pinMode(AIRTAK_PIN_CHG_STAT,   INPUT_PULLUP);
#endif
}

void Battery::sample(EventGroupHandle_t system_event_group) {
    // Oversample 8× to suppress ESP32 ADC noise (~3 bits).
    uint32_t raw_acc = 0;
    for (int i = 0; i < 8; i++) {
        raw_acc += analogRead(AIRTAK_PIN_VBAT_SENSE);
        delay(1);
    }
    uint32_t raw_adc = raw_acc / 8;
    s_voltage_mv = (uint32_t)(raw_adc * ADC_REFERENCE_MV * 2.0f * AIRTAK_ADC_MULTIPLIER
                 / ADC_MAX_COUNTS);

    // Clamp and normalise
    if (s_voltage_mv >= 4200) s_level = 1.0f;
    else if (s_voltage_mv <= 3200) s_level = 0.0f;
    else s_level = (float)(s_voltage_mv - 3200) / (4200 - 3200);

    // CHG_STAT: active low = charging (typical TP4054 behavior)
#if AIRTAK_PIN_CHG_STAT >= 0
    s_is_charging = (digitalRead(AIRTAK_PIN_CHG_STAT) == LOW);
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

} // namespace airtak
