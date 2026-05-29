// AirTAK — Airsoft Team Awareness Kit
// firmware/src/config/constants/DcConstants.h — Centralized duty cycle constants
//
// Constants extracted from firmware/src/config/Defaults.h and
// firmware/src/mesh/DcTracker.h.
//
// Categories:
//   Duty Cycle Thresholds   — Caution, warning, and critical levels
//   TX Log                  — Circular buffer configuration
//   Region Limits           — Per-region airtime caps

#pragma once
#include <cstdint>

namespace airtak {

// ── Duty Cycle Thresholds (§8.7.1) ──────────────────────────────────────────
// Thresholds as percentage of the per-sub-band regulatory limit
constexpr uint8_t  DC_CAUTION_THRESHOLD_PCT   = 60;    // Android notification only
constexpr uint8_t  DC_WARNING_THRESHOLD_PCT   = 80;    // Drop Tier 2 relay; e-ink warning
constexpr uint8_t  DC_CRITICAL_THRESHOLD_PCT  = 95;    // Drop Tier 2; Tier 1 only; e-ink alert

// ── TX Log ──────────────────────────────────────────────────────────────────
constexpr uint8_t  DC_TX_LOG_SIZE             = 128;   // Circular buffer entries

// ── Region-Specific Limits ──────────────────────────────────────────────────
// EU868 G1 sub-band: 10% duty cycle = 360,000 ms per hour
constexpr uint32_t DC_LIMIT_EU868_G1_MS_PER_HOUR = 360000;

// ── Trailing Window ────────────────────────────────────────────────────────
// Duty cycle accumulation window: 1 hour in milliseconds
constexpr uint32_t DC_TRAILING_HOUR_MS        = 3600000; // 1 hour = 3,600,000 ms

} // namespace airtak
