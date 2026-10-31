// AirTAK — Airsoft Team Awareness Kit
// firmware/src/hal/Battery.cpp — ADC battery voltage monitor implementation
// Planning: §7.1, §7.2 | Roadmap: 1.7

#include "hal/Battery.h"
#include "hal/Display.h"
#include "config/ConfigManager.h"
#include "config/constants/Constants.h"
#include "config.h"
#include <Arduino.h>

namespace airtak {

static float    s_level        = 0.0f;
static bool     s_is_charging  = false;
static uint32_t s_voltage_mv   = 0;

// Per-board calibration override (default 1.0 = no adjustment).
// Some boards need a correction for trace resistance, diode/MOSFET drop,
// or ESP32 ADC non-linearity (e.g. T-Deck: 1.08, T-Echo: 1.0).
#ifndef AIRTAK_ADC_MULTIPLIER
#define AIRTAK_ADC_MULTIPLIER AIRTAK_ADC_MULTIPLIER_DEFAULT
#endif
void Battery::init() {
#ifdef AIRTAK_RUNTIME_BOARD
    const auto& profile = platform::selectedBoard();
    if (profile.vbatPin < 0) return;
    analogReadResolution(BATTERY_ADC_RESOLUTION_BITS);
    // Portable metadata values are NOT Arduino enum values. Never cast them.
    switch (profile.adcAttenuation) {
        case AdcAttenuationDb::Db0: analogSetAttenuation(ADC_0db); break;
        case AdcAttenuationDb::Db2_5: analogSetAttenuation(ADC_2_5db); break;
        case AdcAttenuationDb::Db6: analogSetAttenuation(ADC_6db); break;
        case AdcAttenuationDb::None:
        case AdcAttenuationDb::Db11: analogSetAttenuation(ADC_11db); break;
    }
    pinMode(profile.vbatPin, INPUT);
    if (profile.adcCtrlPin >= 0) {
        pinMode(profile.adcCtrlPin, OUTPUT);
        digitalWrite(profile.adcCtrlPin, profile.adcCtrlActiveHigh ? HIGH : LOW);
    }
#else
   analogReadResolution(BATTERY_ADC_RESOLUTION_BITS);
#if defined(AIRTAK_PLATFORM_ESP32)
#if defined(AIRTAK_ADC_ATTENUATION)
    analogSetAttenuation(AIRTAK_ADC_ATTENUATION);
#else
   analogSetAttenuation(ADC_11db);
#endif
#endif
   pinMode(AIRTAK_PIN_VBAT_SENSE, INPUT);
#if AIRTAK_PIN_CHG_STAT >= 0
   pinMode(AIRTAK_PIN_CHG_STAT,   INPUT_PULLUP);
#endif
#if defined(AIRTAK_PIN_ADC_CTRL) && AIRTAK_PIN_ADC_CTRL >= 0
    pinMode(AIRTAK_PIN_ADC_CTRL, OUTPUT);
    // Drive active to enable the voltage divider.  The Heltec V3 enables with
    // LOW (it pulls the divider to GND); the Heltec V4 enables with HIGH
    // (its divider uses a MOSFET that ADC_CTRL turns on). A board that needs
    // active HIGH advertises AIRTAK_ADC_CTRL_ACTIVE_HIGH; otherwise fall back
    // to the Vext polarity (active LOW if Vext is active LOW).
#if defined(AIRTAK_ADC_CTRL_ACTIVE_HIGH)
    digitalWrite(AIRTAK_PIN_ADC_CTRL, HIGH);
#elif defined(AIRTAK_POWER_EN_ACTIVE_LOW)
    digitalWrite(AIRTAK_PIN_ADC_CTRL, LOW);
#else
    digitalWrite(AIRTAK_PIN_ADC_CTRL, HIGH);
#endif
#endif
#endif // AIRTAK_RUNTIME_BOARD
}

void Battery::sample(EventGroupHandle_t system_event_group) {
#ifdef AIRTAK_RUNTIME_BOARD
    if (platform::selectedBoard().vbatPin < 0) return;
#endif
    // Oversample 8× to suppress ESP32 ADC noise (~3 bits).
    uint32_t raw_acc = 0;
    for (int i = 0; i < BATTERY_ADC_OVERSAMPLE; i++) {
        raw_acc += analogRead(AIRTAK_PIN_VBAT_SENSE);
        delay(BATTERY_ADC_DELAY_MS);
    }
    uint32_t raw_adc = raw_acc / BATTERY_ADC_OVERSAMPLE;

    // Per-device ADC multiplier: prefer the value configured over BLE (PowerConfig
    // field 6). When absent/unset the accessor returns 0.0f, so fall back to the
    // board's selected (or legacy compile-time) calibration multiplier.
    float adc_multiplier = config::ConfigManager::powerAdcMultiplier();
    if (adc_multiplier <= 0.0f) adc_multiplier = AIRTAK_ADC_MULTIPLIER;

    s_voltage_mv = (uint32_t)(raw_adc * BATTERY_ADC_REFERENCE_MV * BATTERY_VOLTAGE_DIVIDER_RATIO * adc_multiplier
                 / BATTERY_ADC_MAX_COUNTS);

    // Clamp and normalise
    if (s_voltage_mv >= BATTERY_VOLTAGE_MAX_MV) s_level = 1.0f;
    else if (s_voltage_mv <= BATTERY_VOLTAGE_MIN_MV) s_level = 0.0f;
    else s_level = (float)(s_voltage_mv - BATTERY_VOLTAGE_MIN_MV) / (BATTERY_VOLTAGE_MAX_MV - BATTERY_VOLTAGE_MIN_MV);

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
