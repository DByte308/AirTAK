// AirTAK Android — data/ble/BleConstants.kt
// Planning: §10.1 (GATT service & characteristic UUIDs)
//
// Characteristic UUIDs are derived from the canonical base template in
// config/constants/BleConstants.kt.  Slot 0 = Service, 1–7 = Characteristics.

package com.airtak.data.ble

import com.airtak.config.constants.BLE_RECOVERY_ADV_NAME
import com.airtak.config.constants.BLE_UUID_BASE_TEMPLATE
import java.util.UUID

object BleConstants {
    // ── UUID derivation (§10.1) ──────────────────────────────────────────
    private val uuidBase: String = BLE_UUID_BASE_TEMPLATE
    private fun uuid(slot: Int): UUID = UUID.fromString(uuidBase.format(slot))

    /** AirTAK GATT service (slot 0). */
    val SERVICE_UUID: UUID = uuid(0)

    val CHR_CONFIG_RW: UUID     = uuid(1)
    val CHR_MESH_TX: UUID       = uuid(2)
    val CHR_MESH_RX: UUID       = uuid(3)
    val CHR_DEVICE_STATUS: UUID = uuid(4)
    val CHR_ADMIN: UUID         = uuid(5)
    val CHR_OTA_COMMAND: UUID   = uuid(6)
    val CHR_OTA_DATA: UUID      = uuid(7)

    // ── Recovery advertising (§10.1) ────────────────────────────────────
    /** BLE advertisement name for safe/DFU mode devices.
     *  Canonical: config/constants/BleConstants.kt. */
    const val RECOVERY_ADV_NAME = BLE_RECOVERY_ADV_NAME

    // ── Standard descriptors ────────────────────────────────────────────
    /** Standard Client Characteristic Configuration Descriptor (CCCD). */
    val CCC_DESCRIPTOR: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** All AirTAK characteristics in stable order for iteration. */
    val ALL_CHARS = listOf(
        CHR_CONFIG_RW,
        CHR_MESH_TX,
        CHR_MESH_RX,
        CHR_DEVICE_STATUS,
        CHR_ADMIN,
        CHR_OTA_COMMAND,
        CHR_OTA_DATA,
    )

    /** Subset that requires NOTIFY enablement on connect.
     *  OTA_COMMAND is included so the device's OtaStatus responses (RECEIVING /
     *  COMPLETE / ERROR) reach the app during a firmware update session. */
    val NOTIFY_CHARS = listOf(
        CHR_CONFIG_RW,
        CHR_MESH_RX,
        CHR_DEVICE_STATUS,
        CHR_ADMIN,
        CHR_OTA_COMMAND,
    )
}
