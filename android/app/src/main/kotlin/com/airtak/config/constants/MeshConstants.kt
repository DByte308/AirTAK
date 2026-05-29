// AirTAK Android — config/constants/MeshConstants.kt
// Planning: §8.2 (OTA wire header), §8.3 (Data payload), §8.4 (portnums),
//           §11.6 (mesh repository)
//
// Centralized mesh protocol limits, sync timing, and buffer sizes.

package com.airtak.config.constants

// ── Payload limits (§11.6) ──────────────────────────────
/** Max text message payload in bytes (fits within 237-byte LoRa Data). */
const val MESH_MAX_TEXT_BYTES = 237
/** Conservative upper bound for a TacticalMarker proto within a Data envelope. */
const val MESH_MAX_MARKER_BYTES = 220
/** Max stored messages per channel before oldest are pruned. */
const val MESH_MESSAGE_WINDOW = 1_000

// ── Channel routing ─────────────────────────────────────
/** Synthetic channelIndex for direct (unicast) DM rows. */
const val MESH_DM_CHANNEL_INDEX = -1

// ── Sync protocol ───────────────────────────────────────
/** Gap between re-broadcast messages during sync response. */
const val MESH_SYNC_SPACING_MS = 500L
/** Max number of recent messages re-broadcast on sync request. */
const val MESH_SYNC_MESSAGE_COUNT = 25
/** TTL for handled sync IDs (milliseconds) — prevents re-broadcast loops. */
const val MESH_SYNC_ID_TTL_MS = 10_000L
/** SyncRequest.requested_types bit: marker set. */
const val MESH_SYNC_TYPE_MARKERS = 1
/** SyncRequest.requested_types bit: message set. */
const val MESH_SYNC_TYPE_MESSAGES = 2

// ── Flow buffer capacities ──────────────────────────────
/** Packet flow buffer for incoming mesh traffic. */
const val MESH_INBOUND_BUFFER = 128
/** Config group bytes buffer in ConfigRepository. */
const val MESH_CONFIG_BUFFER = 32

// ── Portnum base values (§8.4) ──────────────────────────
/** Meshtastic TEXT_MESSAGE_APP port number. */
const val PORTNUM_TEXT_MESSAGE_APP = 1
/** Meshtastic POSITION_APP port number. */
const val PORTNUM_POSITION_APP = 3
/** Meshtastic NODEINFO_APP port number. */
const val PORTNUM_NODEINFO_APP = 4
/** Meshtastic ROUTING_APP port number. */
const val PORTNUM_ROUTING_APP = 5
/** Meshtastic TELEMETRY_APP port number. */
const val PORTNUM_TELEMETRY_APP = 67

/** AirTAK portnum base — private range 256–511 (§8.4). */
const val PORTNUM_AIRTAK_BASE = 256

// ── AirTAK custom portnums (§8.4) ───────────────────────
/** AirTAK player state broadcast portnum. */
const val PORTNUM_AIRTAK_PLAYER = 256
/** AirTAK tactical marker portnum. */
const val PORTNUM_AIRTAK_TACTICAL = 257
/** AirTAK moderation/vote portnum. */
const val PORTNUM_AIRTAK_MODERATION = 258
/** AirTAK application-level ACK portnum. */
const val PORTNUM_AIRTAK_ACK = 261
/** AirTAK position request portnum. */
const val PORTNUM_AIRTAK_POSREQ = 262
/** AirTAK audio packet portnum (Phase 7). */
const val PORTNUM_AIRTAK_AUDIO = 263
/** AirTAK sync request portnum. */
const val PORTNUM_AIRTAK_SYNC_REQUEST = 264
/** AirTAK overlay alignment portnum. */
const val PORTNUM_AIRTAK_OVERLAY_ALIGNMENT = 265
/** AirTAK marker snapshot request portnum. */
const val PORTNUM_AIRTAK_MARKER_SNAPSHOT_REQUEST = 266

// ── OTA header (§8.2) ───────────────────────────────────
/** AirTAK OTA header size in bytes (see ProtoConstants for offsets). */
const val MESH_OTA_HEADER_BYTES = 16

// ── EncodeData buffer estimate ──────────────────────────
/** Approximate extra bytes for protobuf envelope in encodeData(). */
const val MESH_ENCODE_ENVELOPE_BYTES = 16

// ── Channel slot routing ────────────────────────────────
/** Bitmask for channel slot index (3 bits = slots 0–7). */
const val MESH_CHANNEL_SLOT_MASK = 0x07

// ── Sync ID cache ───────────────────────────────────────
/** Max entries in the handled-sync-ids LRU cache. */
const val MESH_SYNC_ID_CACHE_SIZE = 8

// ── AES / Crypto ────────────────────────────────────────
/** AES-256 key size in bytes. */
const val MESH_AES_KEY_BYTES = 32
