// AirTAK Android — config/constants/MapConstants.kt
// Planning: §11.5 (map & tiles)
//
// Centralized map defaults, zoom levels, mercator bounds, and tile limits.

package com.airtak.config.constants

// ── Zoom defaults ───────────────────────────────────────
/** Default map zoom level on app launch. */
const val MAP_DEFAULT_ZOOM = 13.0
/** Zoom level when centering on own position. */
const val MAP_SELF_ZOOM = 14.0
/** Zoom level when locating another player/marker. */
const val MAP_LOCATE_ZOOM = 16.0
/** Nudge in density pixels for marker label offset. */
const val MAP_NUDGE_PX = 2f

// ── Web Mercator bounds ─────────────────────────────────
/** Southernmost latitude in Web Mercator projection. */
const val MAP_MERCATOR_MIN_LAT = -85.05112878
/** Northernmost latitude in Web Mercator projection. */
const val MAP_MERCATOR_MAX_LAT = 85.05112878
/** Westernmost longitude in Web Mercator. */
const val MAP_MERCATOR_MIN_LON = -180.0
/** Easternmost longitude. */
const val MAP_MERCATOR_MAX_LON = 180.0

// ── Tile download ───────────────────────────────────────
/** Overall cap on tiles downloaded in a single region download. */
const val MAP_MAX_TILES_PER_DOWNLOAD = 15_000
/** How long a route-calculation failure stays on screen. Nothing to do with tile pacing. */
const val MAP_ROUTE_ERROR_DISMISS_MS = 4000L
/** MapLibre style json uses tileSize 256. */
const val MAP_TILE_SIZE_PX = 256

// ── Routing ─────────────────────────────────────────────
/** Grid cell size in degrees for BRouter segment offloading. */
const val ROUTING_GRID_SIZE_DEG = 5

// ── Player staleness (§11.6) ────────────────────────────
/** Duration after which a player position is considered stale. */
const val MAP_PLAYER_STALE_MS = 5 * 60 * 1_000L
/** Duration after which position data is considered stale. */
const val MAP_POSITION_STALE_MS = 5 * 60 * 1_000L
