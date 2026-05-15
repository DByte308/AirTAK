// AirTAK Android — domain/usecase/ObservePlayersUseCase.kt
// Planning: §12.1, §11.7 | Roadmap: 3.3, 5.2

package com.airtak.domain.usecase

import com.airtak.data.game.GameRepository
import javax.inject.Inject

class ObservePlayersUseCase @Inject constructor(
    private val gameRepository: GameRepository
) {
    // TODO Phase 3.3: Return Flow<List<PlayerState>> from GameRepository.
    // Updated by incoming AIRTAK_PLAYER packets received on MESH_RX characteristic.
    operator fun invoke() = gameRepository.playersFlow()
}
