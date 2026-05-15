// MASH Android — domain/usecase/SendMessageUseCase.kt
// Planning: §11.6 | Roadmap: 3.2

package com.mash.domain.usecase

import com.mash.data.ble.BleRepository
import javax.inject.Inject

class SendMessageUseCase @Inject constructor(
    private val bleRepository: BleRepository
) {
    // TODO Phase 3.2: Serialize TEXT_MESSAGE_APP MeshPacket (portnum=1) and send via
    // BleRepository.writeMeshTx() → MESH_TX characteristic. Max 228 payload bytes (§8.3).
    suspend operator fun invoke(channelIndex: Int, text: String) {
        // TODO Phase 3.2: build and send message
    }
}
