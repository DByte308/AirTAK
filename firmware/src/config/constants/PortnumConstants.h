// AirTAK — Airsoft Team Awareness Kit
// firmware/src/config/constants/PortnumConstants.h — Centralized mesh port number constants
//
// The Portnum enum is defined in mesh/Portnums.h (canonical location).
// This file provides additional portnum-related constants.

#pragma once
#include <cstdint>

namespace airtak {

// ── AirTAK Portnum Range Bounds (§8.5) ───────────────────────────────────
// AirTAK private portnum range: 256–511
constexpr uint32_t PORTNUM_AIRTAK_BASE  = 256;
constexpr uint32_t PORTNUM_AIRTAK_MAX   = 511;

// Highest currently assigned AirTAK portnum (marker snapshot request)
constexpr uint32_t PORTNUM_AIRTAK_MAX_USED  = 266;

} // namespace airtak
