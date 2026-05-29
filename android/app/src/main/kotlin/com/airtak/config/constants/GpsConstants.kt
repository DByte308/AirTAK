// AirTAK Android — config/constants/GpsConstants.kt
// Planning: §11.7 (phone GPS provider)
//
// Centralized phone GPS throttling constants.

package com.airtak.config.constants

// ── Location updates (§11.7) ────────────────────────────
/** Minimum time interval between Android location updates (ms). */
const val GPS_MIN_TIME_MS = 5_000L
/** Minimum displacement before a new location update is emitted (m). */
const val GPS_MIN_DISTANCE_M = 5.0f
