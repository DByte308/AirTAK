// MASH Android — ui/players/PlayersViewModel.kt
// Planning: §11.7 | Roadmap: 3.3, 5.2

package com.mash.ui.players

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class PlayersViewModel @Inject constructor(
    // TODO Phase 3.3: Inject ObservePlayersUseCase, ManageGameUseCase
) : ViewModel() {
    // TODO Phase 3.3: StateFlow<Map<String, List<PlayerState>>> grouped by team
    // TODO Phase 5.4: fun eliminatePlayer(nodeId: Long)
    // TODO Phase 5.4: fun setRespawnTimer(nodeId: Long, seconds: Int)
}
