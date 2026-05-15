// AirTAK Android — domain/usecase/ManageGameUseCase.kt
// Planning: §12, §11.8 | Roadmap: 5.1–5.6

package com.airtak.domain.usecase

import com.airtak.data.ble.BleRepository
import javax.inject.Inject

class ManageGameUseCase @Inject constructor(
    private val bleRepository: BleRepository
) {
    // TODO Phase 5.6: Send AdminRequest via ADMIN characteristic.
    // Admin authentication: device stores admin PSK (SecurityConfig.admin_psk).
    // All admin commands are validated server-side.
    suspend fun startGame(modeOrdinal: Int, durationSecs: Int) {
        // TODO Phase 5.6
    }
    suspend fun pauseGame()  { /* TODO Phase 5.6 */ }
    suspend fun endGame()    { /* TODO Phase 5.6 */ }
    suspend fun eliminatePlayer(nodeId: Long) { /* TODO Phase 5.4 */ }
    suspend fun setRespawnTimer(nodeId: Long, seconds: Int) { /* TODO Phase 5.4 */ }
}
