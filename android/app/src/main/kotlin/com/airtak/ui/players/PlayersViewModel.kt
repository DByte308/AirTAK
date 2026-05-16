// AirTAK Android — ui/players/PlayersViewModel.kt

package com.airtak.ui.players

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.airtak.data.config.ConfigRepository
import com.airtak.data.db.RemoteNodeEntity
import com.airtak.data.game.GameRepository
import com.airtak.data.mesh.MeshRepository
import com.airtak.data.mesh.RemoteNodeRepository
import com.airtak.data.status.DeviceStatusRepository
import com.airtak.proto.AdminAction
import com.airtak.proto.AdminPacket
import com.airtak.proto.ConfigGroup
import com.airtak.proto.GameConfig
import com.airtak.proto.PlayerStatePacket
import com.airtak.proto.PlayerStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlayerRow(
    val nodeId: Long,
    val callsign: String,
    val teamId: Int,
    val teamName: String,
    val colorRgb: Int,
    val status: PlayerStatus,
    val lastSeenMs: Long,
    val lastPlayerMs: Long?,
    val batteryPercent: Int?,
    val isSelf: Boolean,
    val online: Boolean,
)

data class TeamGroup(
    val teamId: Int,
    val name: String,
    val colorRgb: Int,
    val players: List<PlayerRow>,
)

data class PlayersUiState(
    val showOnlineOnly: Boolean = false,
    val isAdmin: Boolean = false,
    val ownStatus: PlayerStatus = PlayerStatus.PLAYER_STATUS_ACTIVE,
    val groups: List<TeamGroup> = emptyList(),
    val onlineCount: Int = 0,
    val totalCount: Int = 0,
    val query: String = "",
)

@HiltViewModel
class PlayersViewModel @Inject constructor(
    private val mesh: MeshRepository,
    private val config: ConfigRepository,
    statusRepo: DeviceStatusRepository,
    remoteNodes: RemoteNodeRepository,
    gameRepository: GameRepository,
) : ViewModel() {
    private val showOnlineOnly = MutableStateFlow(false)
    private val ownStatus = MutableStateFlow(PlayerStatus.PLAYER_STATUS_ACTIVE)
    private val _query = MutableStateFlow("")
    private val status = statusRepo.status
    private val clock: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(30_000)
        }
    }

    private data class Source(
        val nodes: List<RemoteNodeEntity>,
        val deviceStatus: com.airtak.proto.DeviceStatus?,
        val gameConfig: GameConfig,
        val game: com.airtak.data.game.GameMirror,
    )

    init {
        viewModelScope.launch { config.read(ConfigGroup.GAME) }
    }

    private val gameConfig = config.groupBytes.map { map ->
        map[ConfigGroup.GAME]?.let { runCatching { GameConfig.parseFrom(it) }.getOrNull() }
            ?: GameConfig.getDefaultInstance()
    }

    private val source = combine(remoteNodes.nodes, status, gameConfig, gameRepository.state) { nodes, deviceStatus, cfg, game ->
        Source(nodes, deviceStatus, cfg, game)
    }

    val uiState: StateFlow<PlayersUiState> = combine(
        combine(source, showOnlineOnly, ownStatus, clock) { source, onlineOnly, own, now ->
            val ownNodeId = source.deviceStatus?.nodeId?.toLong()?.and(0xFFFF_FFFFL)
            val deviceStatus = source.deviceStatus
            val remoteRows = source.nodes
                .filter { it.nodeId != ownNodeId }
                .map { it.toPlayerRow(now, false, source.game.teams) }
            val ownRow = if (deviceStatus != null) {
                val selfNodeId = deviceStatus.nodeId.toLong() and 0xFFFF_FFFFL
                PlayerRow(
                    nodeId = selfNodeId,
                    callsign = source.gameConfig.callsign.ifBlank { deviceStatus.nodeName.ifBlank { "You" } },
                    teamId = source.gameConfig.teamId,
                    teamName = teamName(source.gameConfig.teamId, source.game.teams),
                    colorRgb = teamColor(source.gameConfig.teamId, source.game.teams),
                    status = own,
                    lastSeenMs = now,
                    lastPlayerMs = now,
                    batteryPercent = (deviceStatus.batteryLevel * 100).toInt().coerceIn(0, 100),
                    isSelf = true,
                    online = true,
                )
            } else null
            val rows = (listOfNotNull(ownRow) + remoteRows)
                .filter { !onlineOnly || it.online }
                .sortedWith(compareBy<PlayerRow> { it.teamId }.thenBy { it.callsign.lowercase() })
            val groups = rows.groupBy { it.teamId }.map { (teamId, players) ->
                TeamGroup(
                    teamId = teamId,
                    name = teamName(teamId, source.game.teams),
                    colorRgb = teamColor(teamId, source.game.teams),
                    players = players,
                )
            }
            PlayersUiState(
                showOnlineOnly = onlineOnly,
                isAdmin = source.gameConfig.isAdmin,
                ownStatus = own,
                groups = groups,
                onlineCount = (listOfNotNull(ownRow) + remoteRows).count { it.online },
                totalCount = (listOfNotNull(ownRow) + remoteRows).size,
            )
        },
        _query,
    ) { state, q ->
        if (q.isBlank()) state.copy(query = q)
        else state.copy(
            query = q,
            groups = state.groups.mapNotNull { grp ->
                val filtered = grp.players.filter {
                    it.callsign.contains(q, ignoreCase = true) ||
                        it.teamName.contains(q, ignoreCase = true)
                }
                if (filtered.isEmpty()) null else grp.copy(players = filtered)
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayersUiState())

    fun setShowOnlineOnly(value: Boolean) { showOnlineOnly.value = value }

    fun setQuery(q: String) { _query.value = q }

    fun setOwnStatus(status: PlayerStatus) {
        val transition = ownStatus.value != status
        ownStatus.value = status
        broadcastOwnStatus(status, transition)
    }

    fun refreshOwnState() = broadcastOwnStatus(ownStatus.value, false)

    fun forceStatus(targetNodeId: Long, status: PlayerStatus) {
        val adminNodeId = statusNodeId() ?: return
        val packet = AdminPacket.newBuilder()
            .setAdminNodeId(adminNodeId)
            .setAction(AdminAction.ADMIN_ACTION_FORCE_PLAYER_STATE)
            .setTargetNodeId((targetNodeId and 0xFFFF_FFFFL).toInt())
            .setValue(status.number)
            .build()
        mesh.sendAdminPacket(packet)
    }

    fun reassignTeam(targetNodeId: Long, teamId: Int) {
        val adminNodeId = statusNodeId() ?: return
        val packet = AdminPacket.newBuilder()
            .setAdminNodeId(adminNodeId)
            .setAction(AdminAction.ADMIN_ACTION_FORCE_PLAYER_TEAM)
            .setTargetNodeId((targetNodeId and 0xFFFF_FFFFL).toInt())
            .setValue(teamId.coerceAtLeast(0))
            .build()
        mesh.sendAdminPacket(packet)
    }

    fun requestPosition(nodeId: Long) {
        mesh.requestPosition(nodeId, statusNodeId()?.toLong()?.and(0xFFFF_FFFFL) ?: 0L)
    }

    private fun broadcastOwnStatus(statusValue: PlayerStatus, transition: Boolean) {
        val currentStatus = status.value ?: return
        val cfg = runCatching { uiState.value }.getOrNull()
        val row = cfg?.groups?.flatMap { it.players }?.firstOrNull { it.isSelf }
        val callsign = row?.callsign?.takeIf { it.isNotBlank() } ?: currentStatus.nodeName.ifBlank { "AirTAK" }
        val teamId = row?.teamId ?: 0
        val packet = PlayerStatePacket.newBuilder()
            .setNodeId(currentStatus.nodeId)
            .setCallsign(callsign)
            .setTeamId(teamId)
            .setStatus(statusValue)
            .setColorRgb(row?.colorRgb ?: defaultTeamColor(teamId))
            .setIsStateTransition(transition)
            .build()
        mesh.sendPlayerState(packet)
    }

    private fun statusNodeId(): Int? = status.value?.nodeId

    private fun RemoteNodeEntity.toPlayerRow(
        now: Long,
        isSelf: Boolean,
        teams: Map<Int, com.airtak.data.game.TeamInfo>,
    ): PlayerRow {
        val online = lastPlayerMs != null && now - lastPlayerMs <= PLAYER_STALE_MS
        val displayName = callsign.ifBlank { shortName.ifBlank { nodeId.toString(16).uppercase().takeLast(4) } }
        return PlayerRow(
            nodeId = nodeId,
            callsign = displayName,
            teamId = teamId,
            teamName = teamName(teamId, teams),
            colorRgb = colorRgb.takeIf { it != 0 } ?: teamColor(teamId, teams),
            status = if (online) PlayerStatus.forNumber(statusValue) ?: PlayerStatus.PLAYER_STATUS_ACTIVE else PlayerStatus.UNRECOGNIZED,
            lastSeenMs = lastSeenMs,
            lastPlayerMs = lastPlayerMs,
            batteryPercent = batteryPercent,
            isSelf = isSelf,
            online = online,
        )
    }

    private fun teamName(teamId: Int, teams: Map<Int, com.airtak.data.game.TeamInfo>): String =
        teams[teamId]?.name ?: if (teamId == 0) "Spectators" else "Team $teamId"

    private fun teamColor(teamId: Int, teams: Map<Int, com.airtak.data.game.TeamInfo>): Int =
        teams[teamId]?.colorRgb?.takeIf { it != 0 } ?: defaultTeamColor(teamId)

    private fun defaultTeamColor(teamId: Int): Int {
        if (teamId <= 0) return 0x6E7681
        return DEFAULT_TEAM_COLORS[(teamId - 1) % DEFAULT_TEAM_COLORS.size]
    }

    private companion object {
        const val PLAYER_STALE_MS = 5 * 60 * 1_000L
        val DEFAULT_TEAM_COLORS = intArrayOf(
            0xE63946,
            0x1D9BF0,
            0xFFB300,
            0x00E676,
            0xB388FF,
            0xFF7043,
            0x80CBC4,
            0xF06292,
        )
    }
}