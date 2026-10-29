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

// ── Last-known seeding (§11.7) ──────────────────────────
/**
 * A cached last-known fix is used to centre the map only if it is no older than this.
 *
 * Android keeps last-known locations for a long time; an hours-old fix would centre the map on
 * where the user last happened to be, which reads as a broken recenter rather than as stale data.
 */
const val GPS_LAST_KNOWN_MAX_AGE_MS = 60_000L
/** Cached fixes worse than this are not used for centring (m). */
const val GPS_LAST_KNOWN_MAX_ACCURACY_M = 50.0f
