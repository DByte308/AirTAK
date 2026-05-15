// MASH Android — data/ble/BleRepository.kt
// Planning: §11.3 | Roadmap: 3.1
//
// Manages BLE scan → pair → connect → reconnect lifecycle.
// Reconnect strategy: exponential backoff (1 s, 2 s, 4 s, … capped at 60 s).
// All BLE callbacks are bridged to Kotlin Flows for consumption by ViewModels.

package com.mash.data.ble

import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BleRepository @Inject constructor() {

    // TODO Phase 3.1: Flow<ConnectionState> (SCANNING, CONNECTING, CONNECTED, DISCONNECTED)
    fun connectionStateFlow(): Flow<Nothing> = TODO("Phase 3.1")

    // TODO Phase 3.5: Flow<ByteArray> from DEVICE_STATUS notifications
    fun deviceStatusFlow(): Flow<ByteArray> = TODO("Phase 3.5")

    // TODO Phase 3.2: Flow<ByteArray> from MESH_RX notifications
    fun meshRxFlow(): Flow<ByteArray> = TODO("Phase 3.2")

    // TODO Phase 3.2: Write payload to MESH_TX characteristic (authenticated, no response).
    suspend fun writeMeshTx(data: ByteArray) { /* TODO Phase 3.2 */ }

    // TODO Phase 3.6: Write to CONFIG_RW characteristic (chunked via BleFraming).
    suspend fun writeConfig(pbData: ByteArray) { /* TODO Phase 3.6 */ }

    // TODO Phase 5.6: Write to ADMIN characteristic (single MTU, no framing).
    suspend fun writeAdmin(pbData: ByteArray) { /* TODO Phase 5.6 */ }
}
