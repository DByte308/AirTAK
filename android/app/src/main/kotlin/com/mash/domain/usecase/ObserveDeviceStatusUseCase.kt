// MASH Android — domain/usecase/ObserveDeviceStatusUseCase.kt
// Planning: §10.4 | Roadmap: 3.5

package com.mash.domain.usecase

import com.mash.data.ble.BleRepository
import javax.inject.Inject

class ObserveDeviceStatusUseCase @Inject constructor(
    private val bleRepository: BleRepository
) {
    // TODO Phase 3.5: Return Flow<DeviceStatus> from BleRepository.deviceStatus
    // Backed by DEVICE_STATUS BLE notifications (§10.4).
    operator fun invoke() = bleRepository.deviceStatusFlow()
}
