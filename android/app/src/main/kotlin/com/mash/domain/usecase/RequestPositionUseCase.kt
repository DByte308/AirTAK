// MASH Android — domain/usecase/RequestPositionUseCase.kt
// Planning: §8.12, §12 | Roadmap: 2.12

package com.mash.domain.usecase

import com.mash.data.ble.BleRepository
import javax.inject.Inject

class RequestPositionUseCase @Inject constructor(
    private val bleRepository: BleRepository
) {
    // TODO Phase 2.12: Send MASH_POSITION_REQUEST via BLE MESH_TX.
    // Firmware forwards it on the mesh; target node responds with POSITION_APP within 2 s.
    suspend operator fun invoke(targetNodeId: Long) {
        // TODO Phase 2.12
    }
}
