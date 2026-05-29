// AirTAK — Airsoft Team Awareness Kit
// firmware/src/config/constants/RadioConstants.h — Centralized radio constants
//
// Constants extracted from firmware/src/hal/Radio.h, firmware/src/hal/Radio.cpp,
// and firmware/src/config/Defaults.h.
//
// Categories:
//   LoRa Defaults       — Region, TX power, sync word
//   PHY Profiles        — Profile count and user-defined profile index
//   PHY Limits          — Spreading factor and coding rate bounds
//   SX1262 Hardware     — Current limit, TX clamp register, TCXO voltage
//   Timing              — Reset timing, poll intervals

#pragma once
#include <cstdint>

namespace airtak {

// ── LoRa Defaults ───────────────────────────────────────────────────────────
constexpr uint8_t  LORA_REGION_DEFAULT       = 0;     // EU_868 default region
constexpr uint8_t  LORA_TX_POWER_DBM_DEFAULT = 22;    // SX1262 maximum TX power
constexpr uint8_t  LORA_SYNC_WORD            = 0x2B;  // Meshtastic sync word (from config)

// ── PHY Profile Count ──────────────────────────────────────────────────────
constexpr uint8_t  PHY_PROFILE_COUNT         = 5;     // Number of PHY profiles [0-4]
constexpr uint8_t  PHY_PROFILE_USER_DEFINED  = 4;     // Index of User Defined profile

// ── Spreading Factor Limits (§8.1) ──────────────────────────────────────────
constexpr uint8_t  LORA_SF_MIN               = 7;
constexpr uint8_t  LORA_SF_MAX               = 12;

// ── Coding Rate Limits (§8.1) ───────────────────────────────────────────────
constexpr uint8_t  LORA_CR_MIN               = 5;     // 4/5
constexpr uint8_t  LORA_CR_MAX               = 8;     // 4/8

// ── SX1262 Hardware ─────────────────────────────────────────────────────────
constexpr float    SX1262_CURRENT_LIMIT_MA   = 140.0f; // SX1262 PA current limit
constexpr uint16_t SX1262_REG_TX_CLAMP_CFG   = 0x08B5; // TX clamp configuration register
constexpr uint8_t  SX1262_TX_CLAMP_VALUE     = 0x01;   // TX clamp config value

// ── SX1262 Default TCXO Voltage ─────────────────────────────────────────────
constexpr float    TCXO_VOLTAGE_DEFAULT      = 1.8f;   // Default TCXO voltage for SX1262

// ── Reset Timing (Radio.cpp) ────────────────────────────────────────────────
constexpr uint32_t RADIO_RESET_LOW_MS        = 2;      // Reset pin low duration
constexpr uint32_t RADIO_RESET_RECOVERY_MS   = 10;     // Recovery after reset release

// ── Radio Task Timing ───────────────────────────────────────────────────────
constexpr uint32_t RADIO_FAILED_POLL_MS      = 1000;   // Poll interval when radio init fails
constexpr uint32_t RADIO_EVENT_POLL_MS       = 100;    // Radio event poll interval

// ── Radio Event Bits ────────────────────────────────────────────────────────
// These CANNOT move (they must be accessible without including Radio.h due to FreeRTOS deps)
// They remain in Radio.h for now; referenced here for documentation.

// ── Radio Type Enum (kept in Radio.h for RadioLib includes) ──────────────────
// enum class RadioType : uint8_t { NONE=0, SX1262=1, SX1276=2, SX1280=3, LR1121=4 };

} // namespace airtak
