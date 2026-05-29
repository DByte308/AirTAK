// AirTAK Android — config/constants/AppConstants.kt
// Planning: §2 (build config), §10 (BLE service), §11.9 (settings)
//
// Centralized app-level identifiers, DB/file names, service channel IDs,
// and SharedPreferences keys.

package com.airtak.config.constants

// ── Database ────────────────────────────────────────────
/** Room database filename. */
const val APP_DB_NAME = "airtak.db"

// ── SharedPreferences ───────────────────────────────────
/** SharedPreferences file name. */
const val APP_PREFS_FILE = "airtak.prefs"

/** Preferences key: selected theme mode. */
const val KEY_THEME = "theme_mode"
/** Preferences key: configured tile server URL. */
const val KEY_TILE_URL = "tile_url"
/** Preferences key: bonded device MAC address. */
const val KEY_BONDED_ADDR = "bonded_addr"
/** Preferences key: default map zoom level. */
const val KEY_MAP_ZOOM = "map_zoom"
/** Default map zoom float value when no preference stored. */
const val MAP_ZOOM_DEFAULT = 13f
/** Default unit channel slot value when unconfigured. */
const val UNIT_CHANNEL_SLOT_DEFAULT = -1
/** Preferences key prefix for unit channel slot bindings. */
const val KEY_PREFIX_UNIT_CHANNEL = "unit_channel_"

// ── Foreground service (§10.3) ──────────────────────────
/** Notification channel ID for the BLE link foreground service. */
const val LINK_CHANNEL_ID = "airtak.link"
/** Notification ID for the BLE link foreground notification. */
const val LINK_NOTIFICATION_ID = 0xA17A

// ── Intent actions ──────────────────────────────────────
/** Intent action to start the BLE link service. */
const val ACTION_LINK_START = "com.airtak.action.LINK_START"
/** Intent action to disconnect and stop the link service. */
const val ACTION_LINK_DISCONNECT = "com.airtak.action.LINK_DISCONNECT"

// ── QR config scheme ────────────────────────────────────
/** URI scheme for AirTAK config QR codes. */
const val QR_SCHEME = "airtak-config://"

// ── Network ─────────────────────────────────────────────
/** Default OpenStreetMap raster tile URL template. */
const val NETWORK_DEFAULT_TILE_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
/** Nominatim geocoding API endpoint. */
const val NETWORK_NOMINATIM_URL = "https://nominatim.openstreetmap.org/search"
/** BRouter segments API endpoint. */
const val NETWORK_BROUTER_SEGMENTS_URL = "https://brouter.de/brouter/segments4/"
/** Default User-Agent string for HTTP requests. */
const val NETWORK_USER_AGENT = "AirTAK/0.1"

// ── Device status defaults ──────────────────────────────
/** Default config value for broadcast interval (sec). */
const val DEVICE_BROADCAST_INTERVAL_SECS = 90
/** Default config value for minimum TX interval (sec). */
const val DEVICE_MIN_TX_INTERVAL_SECS = 15

// ── Mission overlay prefs keys ───────────────────────────
/** SharedPreferences key for overlay alignment records. */
const val KEY_ALIGNMENTS = "alignments"
/** SharedPreferences key for the active mission overlay. */
const val KEY_OVERLAY = "overlay"

// ── Asset paths ─────────────────────────────────────────
/** Filename of the bundled world-overview MBTiles file in assets/tiles/. */
const val ASSET_WORLD_OVERVIEW_FILE = "world-overview.mbtiles"
