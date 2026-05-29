// AirTAK — Airsoft Team Awareness Kit
// firmware/src/config/constants/BatteryConstants.h — ADC and voltage thresholds
// Planning: §7.1, §7.2
//
// ADC conversion parameters, voltage thresholds for charge level calculation,
// and the battery update event bit for the system event group.

#pragma once
#include <cstdint>

namespace airtak {

// ── ADC Parameters ─────────────────────────────────────────────────────────
constexpr uint32_t BATTERY_ADC_REFERENCE_MV    = 3300;   // ADC reference voltage (mV)
constexpr uint32_t BATTERY_ADC_MAX_COUNTS      = 4095;   // 12-bit ADC max reading
constexpr uint8_t  BATTERY_ADC_RESOLUTION_BITS = 12;     // ADC resolution in bits
constexpr uint8_t  BATTERY_ADC_OVERSAMPLE      = 8;      // Oversample count for noise reduction
constexpr uint32_t BATTERY_ADC_DELAY_MS        = 1;      // Delay between oversamples (ms)

// ── Voltage Thresholds (mV) ────────────────────────────────────────────────
constexpr uint32_t BATTERY_VOLTAGE_MAX_MV      = 4200;   // Full charge (4.2V LiPo)
constexpr uint32_t BATTERY_VOLTAGE_MIN_MV      = 3200;   // Empty / cutoff (3.2V)

// ── Default Multiplier (overridden per-board via AIRTAK_ADC_MULTIPLIER) ────
#define AIRTAK_ADC_MULTIPLIER_DEFAULT 1.0f

// ── Battery Event Bit ──────────────────────────────────────────────────────
// Posted to system_event_group after each battery sample completes.
// Defined as uint32_t for portability (EventBits_t is uint32_t on all platforms).
constexpr uint32_t BATTERY_EVENT_UPDATED       = (1 << 12);

} // namespace airtak
