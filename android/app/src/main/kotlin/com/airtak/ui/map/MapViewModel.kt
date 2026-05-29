// AirTAK Android — ui/map/MapViewModel.kt

package com.airtak.ui.map

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.airtak.data.config.ConfigRepository
import com.airtak.data.db.RemoteNodeEntity
import com.airtak.data.db.TacticalMarkerEntity
import com.airtak.data.db.WaypointDao
import com.airtak.data.db.WaypointEntity
import com.airtak.data.geocoding.GeocodingRepository
import com.airtak.data.geocoding.GeocodingResult
import com.airtak.data.gps.PhoneGpsProvider
import com.airtak.data.marker.TacticalMarkerRepository
import com.airtak.data.mesh.MeshRepository
import com.airtak.data.mesh.RemoteNodeRepository
import com.airtak.data.overlays.MissionOverlay
import com.airtak.data.overlays.MissionOverlayCornerId
import com.airtak.data.overlays.MissionOverlayCorners
import com.airtak.data.overlays.MissionOverlayPoint
import com.airtak.data.overlays.MissionOverlayRepository
import com.airtak.data.overlays.OverlayAlignmentRepository
import com.airtak.data.overlays.ReceivedAlignment
import com.airtak.data.routing.RouteCalcState
import com.airtak.data.routing.RoutePoint
import com.airtak.data.routing.RoutingRepository
import com.airtak.data.status.DeviceStatusRepository
import com.airtak.data.tiles.TileBounds
import com.airtak.data.tiles.TileDownloadState
import com.airtak.data.tiles.TileRepository
import com.airtak.proto.ConfigGroup
import com.airtak.proto.GameConfig
import com.airtak.proto.MarkerType
import com.airtak.proto.PlayerStatePacket
import com.airtak.proto.PlayerStatus
import com.airtak.proto.Scope
import com.airtak.proto.TeamControl
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
import com.airtak.config.constants.MAP_LOCATE_ZOOM
import com.airtak.config.constants.MAP_PLAYER_STALE_MS
import com.airtak.config.constants.MAP_POSITION_STALE_MS
import com.airtak.config.constants.PLAYER_STATUS_UNKNOWN
import com.airtak.config.constants.MAP_SELF_ZOOM
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OwnPosition(val latitude: Double, val longitude: Double, val label: String = "")

data class MapNode(
    val nodeId: Long,
    val label: String,
    val latitude: Double,
    val longitude: Double,
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

/** Drawing tool currently active on the map toolbar. */
enum class MarkerTool(val proto: MarkerType, val displayLabel: String, val minPoints: Int, val maxPoints: Int) {
    POINT(MarkerType.MARKER_TYPE_POINT, "Point", 1, 1),
    NOTE(MarkerType.MARKER_TYPE_NOTE, "Note", 1, 1),
    OBJECTIVE(MarkerType.MARKER_TYPE_OBJECTIVE, "Objective", 1, 1),
    ROUTE(MarkerType.MARKER_TYPE_ROUTE, "Route", 2, 12),
    AREA(MarkerType.MARKER_TYPE_AREA, "Area", 3, 12),
}

sealed interface DrawingState {
    data object Idle : DrawingState
    data class Active(val tool: MarkerTool, val points: List<Pair<Double, Double>>) : DrawingState
}

/** Lightweight view of a tactical marker for map rendering. */
data class MarkerVisual(
    val markerId: Long,
    val type: MarkerType,
    val label: String,
    val description: String,
    val colorRgb: Int,
    val scope: Scope,
    val creatorNodeId: Long,
    val ownedLocally: Boolean,
    val controllingTeam: TeamControl,
    val priority: Int,
    val points: List<Pair<Double, Double>>,
    val updatedAtUnix: Long,
)

data class MapUiState(
    val ownPosition: OwnPosition? = null,
    val nodes: List<MapNode> = emptyList(),
    val waypoints: List<MapWaypoint> = emptyList(),
    val showPlayers: Boolean = true,
    val showWaypoints: Boolean = true,
    val tileTemplate: String? = null,
    val localTileTemplate: String? = null,
    val localTileMaxZoom: Int? = null,
    val configuredTileTemplate: String = "",
    val tileDownload: TileDownloadState = TileDownloadState.Idle,
    val missionOverlay: MissionOverlay? = null,
)

@HiltViewModel
class MapViewModel @Inject constructor(
    statusRepo: DeviceStatusRepository,
    remoteNodes: RemoteNodeRepository,
    private val waypointDao: WaypointDao,
    private val mesh: MeshRepository,
    private val tileRepository: TileRepository,
    private val missionOverlayRepository: MissionOverlayRepository,
    private val config: ConfigRepository,
    private val phoneGps: PhoneGpsProvider,
    private val geocodingRepository: GeocodingRepository,
    private val markerRepo: TacticalMarkerRepository,
    private val gameRepo: com.airtak.data.game.GameRepository,
    private val overlayAlignmentRepo: OverlayAlignmentRepository,
    private val routingRepo: RoutingRepository,
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
    private data class TileState(
        val baseTemplate: String?,
        val localTemplate: String?,
        val localMaxZoom: Int?,
        val configuredTemplate: String,
        val download: TileDownloadState,
    )
    private data class SourceState(
        val status: com.airtak.proto.DeviceStatus?,
        val nodes: List<RemoteNodeEntity>,
        val waypoints: List<WaypointEntity>,
        val gameConfig: GameConfig,
    )

    private val layerState = combine(showPlayers, showWaypoints) { players, waypoints ->
        LayerState(players, waypoints)
    }

    init {
        viewModelScope.launch { config.read(ConfigGroup.GAME) }
    }

    private val gameConfig = config.groupBytes.map { map ->
        map[ConfigGroup.GAME]?.let { runCatching { GameConfig.parseFrom(it) }.getOrNull() }
            ?: GameConfig.getDefaultInstance()
    }

    private val tileState = combine(
        tileRepository.tileTemplate,
        tileRepository.localTileTemplate,
        tileRepository.localTileMaxZoom,
        tileRepository.configuredTileTemplate,
        tileRepository.downloadState,
    ) { baseTemplate, localTemplate, localMaxZoom, configuredTemplate, download ->
        TileState(baseTemplate, localTemplate, localMaxZoom, configuredTemplate, download)
    }

    private val sourceState = combine(
        statusRepo.status,
        remoteNodes.nodes,
        waypointDao.observeAll(),
        gameConfig,
    ) { status, nodes, waypoints, gameConfig ->
        SourceState(status, nodes, waypoints, gameConfig)
    }

    private val mapCore = combine(sourceState, layerState, clock, phoneGps.location) { source, layers, now, phoneLoc ->
        val ownLabel = source.gameConfig.callsign.ifBlank { source.status?.nodeName.orEmpty().ifBlank { "You" } }
        val own = source.status?.gps?.takeIf { it.fix }?.let { OwnPosition(it.latitude, it.longitude, ownLabel) }
            ?: phoneLoc?.let { OwnPosition(it.latitude, it.longitude, ownLabel) }
        val ownNodeId = source.status?.nodeId?.toLong()?.and(0xFFFF_FFFFL)
        MapUiState(
            ownPosition = own,
            nodes = source.nodes
                .filter { it.nodeId != ownNodeId && it.latitude != null && it.longitude != null }
                .map { it.toMapNode(now) },
            waypoints = source.waypoints.map { it.toMapWaypoint() },
            showPlayers = layers.players,
            showWaypoints = layers.waypoints,
        )
    }

    val uiState: StateFlow<MapUiState> = combine(mapCore, tileState, missionOverlayRepository.overlay) { core, tile, overlay ->
        core.copy(
            tileTemplate = tile.baseTemplate,
            localTileTemplate = tile.localTemplate,
            localTileMaxZoom = tile.localMaxZoom,
            configuredTileTemplate = tile.configuredTemplate,
            tileDownload = tile.download,
            missionOverlay = overlay,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MapUiState())

    /** Kept for older composables/tests that only need own GPS position. */
    val ownPosition: StateFlow<Pair<Double, Double>?> = uiState
        .map { it.ownPosition?.let { pos -> pos.latitude to pos.longitude } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setShowPlayers(show: Boolean) { showPlayers.value = show }
    fun setShowWaypoints(show: Boolean) { showWaypoints.value = show }

    private fun broadcastOwnStatus(statusValue: PlayerStatus, transition: Boolean) {
        val device = status.value ?: return
        val cfg = runCatching { uiState.value }.getOrNull()
        val callsign = cfg?.ownPosition?.label?.takeIf { it.isNotBlank() }
            ?: device.nodeName.ifBlank { "AirTAK" }
        val gameConfig = runCatching {
            config.groupBytes.value[ConfigGroup.GAME]?.let {
                GameConfig.parseFrom(it)
            }
        }.getOrNull() ?: GameConfig.getDefaultInstance()
        val packet = PlayerStatePacket.newBuilder()
            .setNodeId(device.nodeId)
            .setCallsign(callsign)
            .setStatus(statusValue)
            .setColorRgb(gameConfig.playerColorRgb.takeIf { it != 0 } ?: 0xFFFFFF)
            .setIsStateTransition(transition)
            .setFireteamId(gameConfig.fireteamId)
            .setSquadId(gameConfig.squadId)
            .setPlatoonId(gameConfig.platoonId)
            .setCompanyId(gameConfig.companyId)
            .build()
        mesh.sendPlayerState(packet)
    }

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

    // ── Tactical markers ──────────────────────────────────────────────────────

    val markers: StateFlow<List<MarkerVisual>> = markerRepo.visibleMarkers
        .map { list -> list.map { it.toVisual() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _drawing = MutableStateFlow<DrawingState>(DrawingState.Idle)
    val drawing: StateFlow<DrawingState> = _drawing

    fun startDrawing(tool: MarkerTool) {
        _drawing.value = DrawingState.Active(tool, emptyList())
    }

    fun cancelDrawing() {
        _drawing.value = DrawingState.Idle
    }

    fun addDrawingPoint(latitude: Double, longitude: Double) {
        val state = _drawing.value as? DrawingState.Active ?: return
        if (state.points.size >= state.tool.maxPoints) return
        _drawing.value = state.copy(points = state.points + (latitude to longitude))
    }

    fun removeLastDrawingPoint() {
        val state = _drawing.value as? DrawingState.Active ?: return
        if (state.points.isEmpty()) return
        _drawing.value = state.copy(points = state.points.dropLast(1))
    }

    /** True when the current drawing has enough points to commit. */
    fun canCommitDrawing(): Boolean {
        val state = _drawing.value as? DrawingState.Active ?: return false
        return state.points.size in state.tool.minPoints..state.tool.maxPoints
    }

    fun commitDrawing(
        label: String,
        description: String,
        colorRgb: Int,
        scopeKind: Scope,
        controllingTeam: TeamControl = TeamControl.TEAM_CONTROL_UNKNOWN,
        priority: Int = 0,
    ) {
        val state = _drawing.value as? DrawingState.Active ?: return
        if (state.points.size !in state.tool.minPoints..state.tool.maxPoints) return
        val safeLabel = label.trim().take(UI_MARKER_LABEL_MAX_LEN).ifBlank { state.tool.displayLabel }
        val safeDesc = description.trim().take(UI_MARKER_DESC_MAX_LEN)
        viewModelScope.launch {
            markerRepo.createMarker(
                type = state.tool.proto,
                label = safeLabel,
                description = safeDesc,
                colorRgb = colorRgb and 0xFFFFFF,
                scopeKind = scopeKind,
                points = state.points,
                controllingTeam = controllingTeam,
                priority = priority,
            )
            _drawing.value = DrawingState.Idle
        }
    }

    fun deleteMarker(markerId: Long) {
        viewModelScope.launch { markerRepo.deleteMarker(markerId) }
    }

    fun updateMarker(
        markerId: Long,
        label: String,
        description: String,
        colorRgb: Int,
        scopeKind: Scope,
        controllingTeam: TeamControl = TeamControl.TEAM_CONTROL_UNKNOWN,
        priority: Int = 0,
    ) {
        val safeLabel = label.trim().take(UI_MARKER_LABEL_MAX_LEN)
        val safeDesc = description.trim().take(UI_MARKER_DESC_MAX_LEN)
        viewModelScope.launch {
            markerRepo.updateMarker(
                markerId = markerId,
                label = safeLabel,
                description = safeDesc,
                colorRgb = colorRgb and 0xFFFFFF,
                scopeKind = scopeKind,
                controllingTeam = controllingTeam,
                priority = priority,
            )
        }
    }

    private fun TacticalMarkerEntity.toVisual(): MarkerVisual = MarkerVisual(
        markerId = markerId,
        type = MarkerType.forNumber(typeValue) ?: MarkerType.MARKER_TYPE_POINT,
        label = label,
        description = description,
        colorRgb = colorRgb,
        scope = Scope.forNumber(scopeValue) ?: Scope.SCOPE_ALL,
        creatorNodeId = creatorNodeId,
        ownedLocally = ownedLocally,
        controllingTeam = TeamControl.forNumber(controllingTeamValue) ?: TeamControl.TEAM_CONTROL_UNKNOWN,
        priority = priority,
        points = points(),
        updatedAtUnix = updatedAtUnix,
    )

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

    fun focusMarker(markerId: Long) {
        val m = markers.value.firstOrNull { it.markerId == markerId } ?: return
        val first = m.points.firstOrNull() ?: return
        _focusTarget.value = OwnPosition(first.first, first.second)
    }

    // --- Player status (§5.2) ---
    private val _playerStatus = MutableStateFlow(PlayerStatus.PLAYER_STATUS_ACTIVE)
    val playerStatus: StateFlow<PlayerStatus> = _playerStatus

    fun setPlayerStatus(status: PlayerStatus) {
        val transition = _playerStatus.value != status
        _playerStatus.value = status
        broadcastOwnStatus(status, transition)
    }

    fun refreshPlayerStatus() = broadcastOwnStatus(_playerStatus.value, false)

    // --- Game Active/Ended toggle (§5.8) ---
    val gameActive: StateFlow<Boolean> =
        gameRepo.state.map { it.active }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setGameActive(active: Boolean) { gameRepo.setActive(active) }

    // --- Location search ---

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _searchResults = MutableStateFlow<List<GeocodingResult>>(emptyList())
    val searchResults: StateFlow<List<GeocodingResult>> = _searchResults

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        if (query.isBlank()) {
            _searchResults.value = emptyList()
        }
    }

    fun searchLocations(query: String) {
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            return
        }
        viewModelScope.launch {
            _isSearching.value = true
            _searchResults.value = geocodingRepository.search(query)
            _isSearching.value = false
        }
    }

    fun selectSearchResult(result: GeocodingResult) {
        _focusTarget.value = OwnPosition(result.latitude, result.longitude, result.displayName)
        _searchQuery.value = ""
        _searchResults.value = emptyList()
    }

    fun clearSearch() {
        _searchQuery.value = ""
        _searchResults.value = emptyList()
    }

    fun downloadVisible(bounds: TileBounds, minZoom: Int = 6, maxZoom: Int = 17, tileUrl: String? = null) {
        tileRepository.download(bounds, minZoom, maxZoom, tileUrl)
    }

    fun importMissionOverlay(uri: Uri, corners: MissionOverlayCorners) {
        viewModelScope.launch {
            missionOverlayRepository.importImage(uri, corners)
        }
    }

    fun moveMissionOverlayCorner(cornerId: MissionOverlayCornerId, latitude: Double, longitude: Double) {
        missionOverlayRepository.updateCorner(cornerId, MissionOverlayPoint(latitude, longitude))
    }

    fun setMissionOverlayOpacity(opacity: Float) {
        missionOverlayRepository.updateOpacity(opacity)
    }

    fun removeMissionOverlay() {
        missionOverlayRepository.remove()
    }

    fun setOverlayShareEnabled(enabled: Boolean) {
        missionOverlayRepository.setShareEnabled(enabled)
    }

    fun broadcastOverlayAlignment() {
        missionOverlayRepository.broadcastAlignment()
    }

    fun importOverlayAlignment(alignment: ReceivedAlignment, imageUri: Uri) {
        viewModelScope.launch {
            missionOverlayRepository.importFromAlignment(alignment, imageUri)
        }
    }

    val overlayShareEnabled: StateFlow<Boolean> = missionOverlayRepository.shareEnabled
    val availableAlignments: StateFlow<List<ReceivedAlignment>> = overlayAlignmentRepo.alignments

    val routeState: StateFlow<RouteCalcState> = routingRepo.routeState
    val segmentDownloadState = routingRepo.downloadState
    val segmentDataReady: StateFlow<Boolean> = routingRepo.isDataReady
    val selectedProfile: StateFlow<String> = routingRepo.selectedProfile
    val availableProfiles: List<String> get() = routingRepo.availableProfiles

    fun setProfile(name: String) { routingRepo.setProfile(name) }

    fun navigateToMarker(marker: MarkerVisual, ownLat: Double, ownLon: Double) {
        val target = marker.points.firstOrNull() ?: return
        if (routingRepo.isDataReady.value) {
            routingRepo.calculateRoute(ownLat, ownLon, target.first, target.second)
        } else {
            routingRepo.beelineRoute(ownLat, ownLon, target.first, target.second)
        }
    }

    fun navigateRoutePoints(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double) {
        if (routingRepo.isDataReady.value) {
            routingRepo.calculateRoute(fromLat, fromLon, toLat, toLon)
        } else {
            routingRepo.beelineRoute(fromLat, fromLon, toLat, toLon)
        }
    }

    fun downloadSegments(bounds: TileBounds) {
        routingRepo.downloadSegments(bounds)
    }

    fun clearRoute() {
        routingRepo.clearRoute()
    }

    private fun RemoteNodeEntity.toMapNode(now: Long): MapNode {
        val label = callsign.ifBlank { shortName.ifBlank { nodeId.toString(16).uppercase().takeLast(4) } }
        return MapNode(
            nodeId = nodeId,
            label = label,
            latitude = latitude ?: 0.0,
            longitude = longitude ?: 0.0,
            colorRgb = colorRgb.takeIf { it != 0 } ?: defaultPlayerColor(nodeId),
            statusValue = statusValue.takeIf { it != PLAYER_STATUS_UNKNOWN }
                ?: PlayerStatus.PLAYER_STATUS_ACTIVE_VALUE,
            positionStale = lastPositionMs == null || now - lastPositionMs > MAP_POSITION_STALE_MS,
            playerOffline = lastPlayerMs == null || now - lastPlayerMs > MAP_PLAYER_STALE_MS,
        )
    }

    private fun WaypointEntity.toMapWaypoint(): MapWaypoint = MapWaypoint(
        id = id,
        label = label,
        icon = icon,
        latitude = latitude,
        longitude = longitude,
    )

    private fun defaultPlayerColor(nodeId: Long): Int {
        return DEFAULT_COLORS[(nodeId % DEFAULT_COLORS.size).toInt().coerceAtLeast(0)]
    }

    private companion object {
        val DEFAULT_COLORS = intArrayOf(
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
