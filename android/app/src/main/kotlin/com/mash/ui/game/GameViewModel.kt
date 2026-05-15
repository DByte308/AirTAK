// MASH Android — ui/game/GameViewModel.kt
// Planning: §11.8, §12 | Roadmap: 5.1–5.6

package com.mash.ui.game

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class GameViewModel @Inject constructor(
    // TODO Phase 5.1: Inject GameRepository, ManageGameUseCase
) : ViewModel() {
    // TODO Phase 5.1: StateFlow<GameState>
    // TODO Phase 5.3: StateFlow<PlayerStatus> for own player
    // TODO Phase 5.6: fun startGame(mode: GameMode, durationSecs: Int)
    // TODO Phase 5.6: fun endGame()
}
