// AirTAK Android — config/constants/BleConstants.kt
// Planning: §10 (BLE service), §10.2 (BLE framing), §11.10 (lifecycle)
//
// Centralized BLE timing, sizing, and protocol constants for the Android
// companion app. UUIDs remain in data/ble/BleConstants.kt (§10.1).

package com.airtak.config.constants

// ── Scan ────────────────────────────────────────────────
/** Auto-stop scan after this duration to conserve battery. */
const val BLE_SCAN_TIMEOUT_MS = 30_000L

// ── GATT / MTU ──────────────────────────────────────────
/** Default BLE MTU before negotiation (BLE 4.x ATT_MTU). */
const val BLE_DEFAULT_MTU = 23
/** Maximum MTU requested from the device (§10.2). */
const val BLE_MAX_MTU_REQUEST = 517
/** Android-side cap on BluetoothGattCharacteristic value length. */
const val BLE_MAX_ATTR_VALUE_BYTES = 512

// ── Notification enablement ─────────────────────────────
/** Inter-enable delay to avoid flooding the GATT queue. */
const val BLE_INTER_NOTIFY_DELAY_MS = 40L

// ── Mesh TX throttle (§11.10) ───────────────────────────
/** Min interval between MESH_TX writes to prevent LoRa congestion. */
const val BLE_MESH_TX_THROTTLE_MS = 500L

// ── Write queue ─────────────────────────────────────────
/** Default number of GATT write retries before giving up. */
const val BLE_TX_RETRY_COUNT = 3
/** Busy-wait poll interval in awaitTxIdle(). */
const val BLE_TX_IDLE_POLL_MS = 20L
/** Backpressure delay after a failed GATT write. */
const val BLE_TX_RETRY_BACKOFF_MS = 20L
/** Default timeout for awaitTxIdle() — used by OTA engines. */
const val BLE_DEFAULT_AWAIT_TX_IDLE_MS = 30_000L

// ── Reconnect backoff (§11.10) ──────────────────────────
/** Base reconnect delay in milliseconds (doubles each attempt). */
const val BLE_RECONNECT_BASE_MS = 1_000L
/** Cap on reconnect backoff. */
const val BLE_RECONNECT_MAX_MS = 60_000L
/** Max exponent for backoff doubling. */
const val BLE_RECONNECT_MAX_EXPONENT = 6

// ── Flow buffer capacities ──────────────────────────────
/** Inbound frame buffer capacity in BleRepository. Drops oldest on overflow. */
const val BLE_INBOUND_BUFFER = 256

// ── Chunk framing (§10.2) ───────────────────────────────
/** BLE chunk frame header size (total_len + chunk_index + chunk_count). */
const val BLE_FRAMING_HEADER_BYTES = 6
/** ATT protocol overhead per write (opcode + handle). */
const val BLE_FRAMING_ATT_OVERHEAD = 3
/** Max total payload bytes for chunk framing (uint16 max). */
const val BLE_FRAMING_MAX_PAYLOAD_BYTES = 65_535

// ── UUID base template (§10.1) ──────────────────────────
/** Base UUID template for AirTAK custom 128-bit UUIDs. Slot 0=Service, 1–7=Characteristics. */
const val BLE_UUID_BASE_TEMPLATE = "4d415348-%04x-1000-8000-00805f9b34fb"

// ── Recovery ────────────────────────────────────────────
/** BLE advertisement name shown by devices in safe/DFU mode. */
const val BLE_RECOVERY_ADV_NAME = "AirTAK Recovery"
