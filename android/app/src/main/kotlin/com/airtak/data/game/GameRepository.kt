// AirTAK Android — data/game/GameRepository.kt
//
// v1.6: Game state is decentralized (Active/Ended toggle per device, §5.8).
// No centralized GameStatePacket. This repository holds the local game-active
// state and broadcasts it via the Active/Ended toggle (Phase 5.8).
// Unit hierarchy and tactical markers are handled in their respective repositories.

package com.airtak.data.game

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class GameState(
    val active: Boolean = true,
)

@Singleton
class GameRepository @Inject constructor() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(GameState())
    val state: StateFlow<GameState> = _state.asStateFlow()

    fun setActive(active: Boolean) {
        _state.value = _state.value.copy(active = active)
    }
}
