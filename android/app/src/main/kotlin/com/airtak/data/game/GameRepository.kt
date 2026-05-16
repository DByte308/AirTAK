// AirTAK Android — data/game/GameRepository.kt

package com.airtak.data.game

import com.airtak.data.mesh.MeshRepository
import com.airtak.data.mesh.Portnum
import com.airtak.proto.GamePhase
import com.airtak.proto.GameStatePacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class TeamInfo(
    val teamId: Int,
    val name: String,
    val colorRgb: Int,
)

data class GameMirror(
    val phase: GamePhase = GamePhase.GAME_PHASE_LOBBY,
    val teams: Map<Int, TeamInfo> = emptyMap(),
    val updatedAtMs: Long = 0,
)

@Singleton
class GameRepository @Inject constructor(
    mesh: MeshRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(GameMirror())
    val state: StateFlow<GameMirror> = _state.asStateFlow()

    init {
        scope.launch {
            mesh.packets
                .filter { it.portnum == Portnum.AIRTAK_GAME && it.payload != null }
                .collect { packet ->
                    val game = runCatching { GameStatePacket.parseFrom(packet.payload) }.getOrNull()
                        ?: return@collect
                    _state.value = GameMirror(
                        phase = game.phase,
                        teams = game.teamsList.associate { team ->
                            team.teamId to TeamInfo(
                                teamId = team.teamId,
                                name = team.name.ifBlank { "Team ${team.teamId}" },
                                colorRgb = team.colorRgb,
                            )
                        },
                        updatedAtMs = System.currentTimeMillis(),
                    )
                }
        }
    }
}