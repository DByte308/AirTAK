// AirTAK Android — config/constants/UiConstants.kt
// Planning: §11.9 (settings UI)
//
// Centralized UI dimension, range, and validation constants.

package com.airtak.config.constants

// ── Text field limits ───────────────────────────────────
/** Max characters for a tile server URL. */
const val UI_TILE_URL_MAX_LEN = 512
/** Max characters for a tactical marker description. */
const val UI_MARKER_DESC_MAX_LEN = 256
/** Max characters for a tactical marker label. */
const val UI_MARKER_LABEL_MAX_LEN = 32
/** Max callsign / channel name length. */
const val UI_CALLSIGN_MAX_LEN = 16
/** Max channel name length. */
const val UI_CHANNEL_NAME_MAX_LEN = 16

// ── Validation limits ───────────────────────────────────
/** Max PSK bytes (32 = AES-256 key). */
const val UI_PSK_MAX_BYTES = 32

// ── Tactical marker limits ──────────────────────────────
/** Max control points (lat/lon pairs) for a route/area marker. */
const val UI_MARKER_MAX_POINTS = 12

// ── TX power slider (§11.9) ─────────────────────────────
const val UI_TX_POWER_MIN_DBM = 2f
const val UI_TX_POWER_MAX_DBM = 22f
const val UI_TX_POWER_STEPS = 19

// ── Max bonds slider ────────────────────────────────────
const val UI_MAX_BONDS_MIN = 1
const val UI_MAX_BONDS_MAX = 4
const val UI_MAX_BONDS_DEFAULT = 2

// ── Brightness slider ───────────────────────────────────
const val UI_BRIGHTNESS_MIN_PCT = 0f
const val UI_BRIGHTNESS_MAX_PCT = 100f
const val UI_BRIGHTNESS_STEPS = 19

// ── QR rendering ────────────────────────────────────────
/** Default QR code bitmap size in pixels. */
const val UI_QR_SIZE_DEFAULT_PX = 512

// ── Bandwidth options (§11.9) ───────────────────────────
/** Available LoRa bandwidth settings in Hz. */
val UI_BANDWIDTH_OPTIONS = listOf(125000, 250000, 500000)

// ── Unit slots ──────────────────────────────────────────
/** Minimum unit-slot index rendered in the settings UI. */
const val UI_MIN_UNIT_SLOT_INDEX = 3
/** Maximum number of unit-slot rows displayed. */
const val UI_MAX_UNIT_SLOTS = 5

// ── Message limits ──────────────────────────────────────
/** Default number of messages fetched per channel. */
const val UI_DEFAULT_MESSAGE_LIMIT = 500

// ── Color masking ───────────────────────────────────────
/** Mask to extract 24-bit RGB from an ARGB int. */
const val UI_RGB_MASK = 0xFFFFFF
/** Alpha channel prepend for full-opacity Color rendering. */
const val UI_ARGB_ALPHA_FULL = 0xFF000000L
/** Mid-gray luminance threshold for contrast-based halo. */
const val UI_HALO_LUMINANCE_THRESHOLD = 0x808080

// ── Distance display thresholds ─────────────────────────
/** Distance below which meters are shown with one decimal. */
const val UI_DISTANCE_PRECISE_THRESHOLD_M = 10
/** Distance at which display switches from meters to km. */
const val UI_DISTANCE_KM_THRESHOLD_M = 1000
/** Conversion from meters to km (display). */
const val UI_METERS_TO_KM = 1000

// ── Time display constants ──────────────────────────────
/** Seconds per minute (for "Xm ago" display). */
const val UI_SECONDS_PER_MINUTE = 60
/** Seconds per hour (for "Xh ago" display). */
const val UI_SECONDS_PER_HOUR = 3600
/** Seconds per day (for "Xd ago" display). */
const val UI_SECONDS_PER_DAY = 86400
/** Milliseconds per second (for Unix timestamp display). */
const val UI_MS_PER_SECOND = 1000L

// ── LoRa link rate formula constants ────────────────────
/** Coding rate numerator in LoRa rate formula. */
const val UI_LORA_RATE_CR_NUMERATOR = 4.0
/** Divisor for converting bps to kbps in LoRa display. */
const val UI_LORA_BPS_TO_KBPS = 1000.0

// ── Bandwidth display ───────────────────────────────────
/** Divisor for displaying Hz bandwidth as kHz. */
const val UI_BANDWIDTH_HZ_TO_KHZ = 1000

// ── Ban display in settings ─────────────────────────────
val UI_BAN_VOTE_FORMAT = "/ ${MODERATION_REQUIRED_VOTES} votes"

// ── Mission overlay defaults ────────────────────────────
/** Default opacity for mission overlay rendering. */
const val UI_OVERLAY_OPACITY_DEFAULT = 0.72f
/** Minimum opacity for mission overlay slider. */
const val UI_OVERLAY_OPACITY_MIN = 0.1f

// ── Player color defaults ───────────────────────────────
/** Default player color RGB (white). */
const val UI_DEFAULT_PLAYER_COLOR_RGB = 0xFFFFFF

// ── File I/O buffers ────────────────────────────────────
/** Stream copy buffer size in bytes. */
const val UI_STREAM_COPY_BUFFER_BYTES = 8192
/** Number of hash bytes used for overlay content ID. */
const val UI_OVERLAY_HASH_BYTES = 8

// ── HTTP cache ──────────────────────────────────────────
/** HTTP max-age header value for tile caching (24 h in seconds). */
const val UI_TILE_CACHE_MAX_AGE_SEC = 86400
