// AirTAK Android — ui/map/MapViewModel.kt

package com.airtak.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.airtak.data.db.RemoteNodeEntity
import com.airtak.data.db.WaypointDao
import com.airtak.data.db.WaypointEntity
import com.airtak.data.game.GameRepository
import com.airtak.data.mesh.MeshRepository
import com.airtak.data.mesh.RemoteNodeRepository
import com.airtak.data.status.DeviceStatusRepository
import com.airtak.data.tiles.TileBounds
import com.airtak.data.tiles.TileDownloadState
import com.airtak.data.tiles.TileRepository
import com.airtak.proto.PlayerStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OwnPosition(val latitude: Double, val longitude: Double)

data class MapNode(
    val nodeId: Long,
    val label: String,
    val latitude: Double,
    val longitude: Double,
    val teamId: Int,
    val teamName: String,
    val colorRgb: Int,
    val statusValue: Int,
    val positionStale: Boolean,
    val playerOffline: Boolean,
)

data class MapWaypoint(
    val id: Long,
    val label: String,
    val icon: String,
    val latitude: Double,
    val longitude: Double,
)

data class MapUiState(
    val ownPosition: OwnPosition? = null,
    val nodes: List<MapNode> = emptyList(),
    val waypoints: List<MapWaypoint> = emptyList(),
    val showPlayers: Boolean = true,
    val showWaypoints: Boolean = true,
    val tileTemplate: String? = null,
    val tileDownload: TileDownloadState = TileDownloadState.Idle,
)

@HiltViewModel
class MapViewModel @Inject constructor(
    statusRepo: DeviceStatusRepository,
    remoteNodes: RemoteNodeRepository,
    gameRepository: GameRepository,
    private val waypointDao: WaypointDao,
    private val mesh: MeshRepository,
    private val tileRepository: TileRepository,
) : ViewModel() {
    private val showPlayers = MutableStateFlow(true)
    private val showWaypoints = MutableStateFlow(true)
    private val status = statusRepo.status
    private val clock: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(30_000)
        }
    }

    private data class LayerState(val players: Boolean, val waypoints: Boolean)
    private data class TileState(val template: String?, val download: TileDownloadState)
    private data class SourceState(
        val status: com.airtak.proto.DeviceStatus?,
        val nodes: List<RemoteNodeEntity>,
        val waypoints: List<WaypointEntity>,
        val game: com.airtak.data.game.GameMirror,
    )

    private val layerState = combine(showPlayers, showWaypoints) { players, waypoints ->
        LayerState(players, waypoints)
    }
    private val tileState = combine(tileRepository.tileTemplate, tileRepository.downloadState) { template, download ->
        TileState(template, download)
    }

    private val sourceState = combine(
        statusRepo.status,
        remoteNodes.nodes,
        waypointDao.observeAll(),
        gameRepository.state,
    ) { status, nodes, waypoints, game ->
        SourceState(status, nodes, waypoints, game)
    }

    private val mapCore = combine(sourceState, layerState, clock) { source, layers, now ->
        val own = source.status?.gps?.takeIf { it.fix }?.let { OwnPosition(it.latitude, it.longitude) }
        val ownNodeId = source.status?.nodeId?.toLong()?.and(0xFFFF_FFFFL)
        MapUiState(
            ownPosition = own,
            nodes = source.nodes
                .filter { it.nodeId != ownNodeId && it.latitude != null && it.longitude != null }
                .map { it.toMapNode(now, source.game.teams) },
            waypoints = source.waypoints.map { it.toMapWaypoint() },
            showPlayers = layers.players,
            showWaypoints = layers.waypoints,
        )
    }

    val uiState: StateFlow<MapUiState> = combine(mapCore, tileState) { core, tile ->
        core.copy(tileTemplate = tile.template, tileDownload = tile.download)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MapUiState())

    /** Kept for older composables/tests that only need own GPS position. */
    val ownPosition: StateFlow<Pair<Double, Double>?> = uiState
        .map { it.ownPosition?.let { pos -> pos.latitude to pos.longitude } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setShowPlayers(show: Boolean) { showPlayers.value = show }
    fun setShowWaypoints(show: Boolean) { showWaypoints.value = show }

    fun addWaypoint(latitude: Double, longitude: Double, label: String, icon: String = "flag") {
        val safeLabel = label.trim().ifBlank { "WP" }.take(24)
        viewModelScope.launch {
            waypointDao.insert(
                WaypointEntity(
                    label = safeLabel,
                    icon = icon,
                    latitude = latitude,
                    longitude = longitude,
                    createdMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun requestPosition(nodeId: Long) {
        val ownNodeId = status.value?.nodeId?.toLong()?.and(0xFFFF_FFFFL) ?: 0L
        mesh.requestPosition(nodeId, ownNodeId)
    }

    /**
     * Emits the lat/lng to fly to. Callers set this via [focusNode]; cleared after the map
     * consumes it via [clearFocus].
     */
    private val _focusTarget = MutableStateFlow<OwnPosition?>(null)
    val focusTarget: StateFlow<OwnPosition?> = _focusTarget

    fun focusNode(nodeId: Long) {
        val node = uiState.value.nodes.firstOrNull { it.nodeId == nodeId }
        if (node != null) {
            _focusTarget.value = OwnPosition(node.latitude, node.longitude)
            return
        }
        // Self — use own GPS position
        val own = uiState.value.ownPosition
        if (own != null) _focusTarget.value = own
    }

    fun clearFocus() { _focusTarget.value = null }

    fun downloadVisible(bounds: TileBounds, minZoom: Int = 6, maxZoom: Int = 17) {
        tileRepository.download(bounds, minZoom, maxZoom)
    }

    private fun RemoteNodeEntity.toMapNode(now: Long, teams: Map<Int, com.airtak.data.game.TeamInfo>): MapNode {
        val team = teams[teamId]
        val label = callsign.ifBlank { shortName.ifBlank { nodeId.toString(16).uppercase().takeLast(4) } }
        return MapNode(
            nodeId = nodeId,
            label = label,
            latitude = latitude ?: 0.0,
            longitude = longitude ?: 0.0,
            teamId = teamId,
            teamName = team?.name ?: if (teamId == 0) "Spectator" else "Team $teamId",
            colorRgb = colorRgb.takeIf { it != 0 } ?: team?.colorRgb ?: defaultTeamColor(teamId),
            statusValue = statusValue.takeIf { it != RemoteNodeEntity.PLAYER_STATUS_UNKNOWN }
                ?: PlayerStatus.PLAYER_STATUS_ACTIVE_VALUE,
            positionStale = lastPositionMs == null || now - lastPositionMs > POSITION_STALE_MS,
            playerOffline = lastPlayerMs == null || now - lastPlayerMs > PLAYER_STALE_MS,
        )
    }

    private fun WaypointEntity.toMapWaypoint(): MapWaypoint = MapWaypoint(
        id = id,
        label = label,
        icon = icon,
        latitude = latitude,
        longitude = longitude,
    )

    private fun defaultTeamColor(teamId: Int): Int {
        if (teamId <= 0) return 0x6E7681
        return DEFAULT_TEAM_COLORS[(teamId - 1) % DEFAULT_TEAM_COLORS.size]
    }

    private companion object {
        const val POSITION_STALE_MS = 5 * 60 * 1_000L
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
