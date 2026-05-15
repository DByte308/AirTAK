// MASH Android — domain/usecase/ObservePlayersUseCase.kt
// Planning: §12.1, §11.7 | Roadmap: 3.3, 5.2

package com.mash.domain.usecase

import com.mash.data.game.GameRepository
import javax.inject.Inject

class ObservePlayersUseCase @Inject constructor(
    private val gameRepository: GameRepository
) {
    // TODO Phase 3.3: Return Flow<List<PlayerState>> from GameRepository.
    // Updated by incoming MASH_PLAYER packets received on MESH_RX characteristic.
    operator fun invoke() = gameRepository.playersFlow()
}
