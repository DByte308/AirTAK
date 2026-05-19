// AirTAK Android — data/ble/BleConstants.kt
// Planning: §10.1 (GATT service & characteristic UUIDs)

package com.airtak.data.ble

import java.util.UUID

object BleConstants {
    // Service UUID — ASCII "AirTAK" = 0x4d415348 in the first 32 bits (§10.1).
    val SERVICE_UUID: UUID = UUID.fromString("4d415348-0000-1000-8000-00805f9b34fb")

    val CHR_CONFIG_RW: UUID     = UUID.fromString("4d415348-0001-1000-8000-00805f9b34fb")
    val CHR_MESH_TX: UUID       = UUID.fromString("4d415348-0002-1000-8000-00805f9b34fb")
    val CHR_MESH_RX: UUID       = UUID.fromString("4d415348-0003-1000-8000-00805f9b34fb")
    val CHR_DEVICE_STATUS: UUID = UUID.fromString("4d415348-0004-1000-8000-00805f9b34fb")
    val CHR_ADMIN: UUID         = UUID.fromString("4d415348-0005-1000-8000-00805f9b34fb")
    val CHR_OTA_COMMAND: UUID   = UUID.fromString("4d415348-0006-1000-8000-00805f9b34fb")
    val CHR_OTA_DATA: UUID      = UUID.fromString("4d415348-0007-1000-8000-00805f9b34fb")

    // BLE Recovery (DFU) advertisement name — used by the safe-mode pairing flow.
    const val RECOVERY_ADV_NAME = "AirTAK Recovery"

    // Standard Client Characteristic Configuration Descriptor.
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
