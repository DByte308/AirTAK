// AirTAK Android — data/game/GameRepository.kt
//
// v1.7 (Phase 5.8): Active/Ended toggle is decentralized. This repository
// holds the local game-active flag and, on change, immediately broadcasts a
// PlayerStatePacket transition (ACTIVE ↔ INACTIVE) so peers update their
// rosters without waiting for the next heartbeat.

package com.airtak.data.game

import com.airtak.data.config.ConfigRepository
import com.airtak.data.mesh.MeshRepository
import com.airtak.data.mesh.applyNamePresence
import com.airtak.data.status.DeviceStatusRepository
import com.airtak.util.LocalNodeIdentity
import com.airtak.config.constants.GAME_DEFAULT_PLAYER_COLOR
import com.airtak.proto.PlayerStatePacket
import com.airtak.proto.PlayerStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class GameState(
    val active: Boolean = true,
)

@Singleton
class GameRepository @Inject constructor(
    private val mesh: MeshRepository,
    private val status: DeviceStatusRepository,
    private val config: ConfigRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(GameState())
    val state: StateFlow<GameState> = _state.asStateFlow()

    init {
        scope.launch {
            _state
                .map { it.active }
                .distinctUntilChanged()
                .drop(1)
                .collect { active -> broadcastActiveTransition(active) }
        }
    }

    fun setActive(active: Boolean) {
        _state.value = _state.value.copy(active = active)
    }

    private fun broadcastActiveTransition(active: Boolean) {
        val device = status.status.value ?: return
        val identity = LocalNodeIdentity.from(config.groupBytes.value)
        val cfg = identity.game
        val callsign = identity.broadcastCallsign()
        val packet = PlayerStatePacket.newBuilder()
            .setNodeId(device.nodeId)
            .setStatus(if (active) PlayerStatus.PLAYER_STATUS_ACTIVE else PlayerStatus.PLAYER_STATUS_INACTIVE)
            .setColorRgb(cfg.playerColorRgb.takeIf { it != 0 } ?: GAME_DEFAULT_PLAYER_COLOR)
            .setIsStateTransition(true)
            .applyNamePresence(callsign, cfg.fireteamId, cfg.squadId, cfg.platoonId, cfg.companyId)
            .build()
        mesh.sendPlayerState(packet)
    }
}
