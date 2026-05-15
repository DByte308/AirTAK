// MASH Android — data/game/GameRepository.kt
// Planning: §12, §11.7 | Roadmap: 5.1–5.6
//
// Maintains in-memory game state and player table updated from BLE MESH_RX notifications.
// Parses MASH_GAME, MASH_PLAYER, MASH_ZONE, MASH_OBJECTIVE packets from the MESH_RX stream.

package com.mash.data.game

import com.mash.domain.model.GameState
import com.mash.domain.model.PlayerState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GameRepository @Inject constructor() {

    private val _gameState    = MutableStateFlow(GameState())
    private val _playerStates = MutableStateFlow<List<PlayerState>>(emptyList())

    fun gameStateFlow(): Flow<GameState>         = _gameState
    fun playersFlow():   Flow<List<PlayerState>> = _playerStates

    /** Called by MeshRepository when a MASH_GAME packet arrives. Phase 5.1. */
    fun onGameStatePacket(pb: ByteArray) {
        // TODO Phase 5.1: nanopb-decode mash.GameState proto; emit updated _gameState.
        // For now: no-op until game proto generation (Phase 5).
    }

    /** Called by MeshRepository when a MASH_PLAYER packet arrives. Phase 5.3. */
    fun onPlayerPacket(pb: ByteArray) {
        // TODO Phase 5.3: nanopb-decode mash.PlayerState proto; upsert into _playerStates.
    }

    /** Called by MeshRepository when a MASH_ZONE packet arrives. Phase 5.2. */
    fun onZonePacket(pb: ByteArray) {
        // TODO Phase 5.2: decode zone data; stored separately from game state.
    }

    /** Called by MeshRepository when a MASH_OBJECTIVE packet arrives. Phase 5.3. */
    fun onObjectivePacket(pb: ByteArray) {
        // TODO Phase 5.3: decode objective update; emit into _gameState.objectives.
    }
}
