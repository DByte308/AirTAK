// AirTAK Android — config/constants/ModerationConstants.kt
// Roadmap: 5.10
//
// Centralized moderation and player retention constants.

package com.airtak.config.constants

// ── Vote-kick ───────────────────────────────────────────
/** Number of distinct voters required to trigger a shadowban. */
const val MODERATION_REQUIRED_VOTES = 3
/** Duration of a ban sliding window (24 h). */
const val MODERATION_BAN_TTL_SEC = 86_400L
/** Interval between periodic prune of stale votes (1 h). */
const val MODERATION_PRUNE_INTERVAL_MS = 60L * 60L * 1_000L

// ── Remote node retention ───────────────────────────────
/** Duration to retain a remote node before expiry (24 h). */
const val MESH_NODE_RETENTION_MS = 24L * 60L * 60L * 1_000L

// ── Marker lifecycle ────────────────────────────────────
/** Channel slot index for scope-all tactical markers. */
const val MESH_TACTICAL_SLOT_ALL = 1
/** Tombstone retention before purge (24 h). */
const val MARKER_TOMBSTONE_TTL_SEC = 24L * 60L * 60L
/** Interval between tombstone purge sweeps (1 h). */
const val MARKER_PURGE_INTERVAL_MS = 60L * 60L * 1_000L
/** Gap between owned-marker rebroadcasts on reconnect. */
const val MARKER_REBROADCAST_SPACING_MS = 500L

// ── Marker ID generation ───────────────────────────────
/** Byte length of a randomly-generated marker ID. */
const val MARKER_ID_BYTES = 4
/** Shift amounts for combining marker ID bytes into a Long. */
const val MARKER_ID_SHIFT_3 = 24
const val MARKER_ID_SHIFT_2 = 16
const val MARKER_ID_SHIFT_1 = 8

// ── Default game colors ─────────────────────────────────
/** Default player color (white). */
const val GAME_DEFAULT_PLAYER_COLOR = 0xFFFFFF

// ── Player status sentinels ──────────────────────────────
/** Sentinel value for unknown/unset player status. */
const val PLAYER_STATUS_UNKNOWN = -1
