// AirTAK Android — ui/map/MapScreen.kt

package com.airtak.ui.map

import android.graphics.BitmapFactory
import android.graphics.PointF
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.outlined.AltRoute
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddLocationAlt
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.airtak.R
import com.airtak.ui.i18n.labelText
import com.airtak.ui.i18n.priorityText
import com.airtak.ui.i18n.uiLabel
import com.airtak.config.constants.MAP_DEFAULT_ZOOM
import com.airtak.config.constants.MAP_LOCATE_ZOOM
import com.airtak.config.constants.MAP_MERCATOR_MAX_LAT
import com.airtak.config.constants.MAP_MERCATOR_MIN_LAT
import com.airtak.config.constants.MAP_MAX_TILES_PER_DOWNLOAD
import com.airtak.config.constants.MAP_NUDGE_PX
import com.airtak.config.constants.MAP_SELF_ZOOM
import com.airtak.config.constants.MAP_ROUTE_ERROR_DISMISS_MS
import com.airtak.config.constants.MAP_TILE_SIZE_PX
import com.airtak.config.constants.UI_ARGB_ALPHA_FULL
import com.airtak.config.constants.UI_DISTANCE_KM_THRESHOLD_M
import com.airtak.config.constants.UI_DISTANCE_PRECISE_THRESHOLD_M
import com.airtak.config.constants.UI_HALO_LUMINANCE_THRESHOLD
import com.airtak.config.constants.UI_MARKER_DESC_MAX_LEN
import com.airtak.config.constants.UI_METERS_TO_KM
import com.airtak.config.constants.UI_RGB_MASK
import com.airtak.config.constants.UI_TILE_URL_MAX_LEN
import com.airtak.data.gps.noPositionReason
import com.airtak.data.tiles.TileBounds
import com.airtak.data.tiles.TileDownloadState
import com.airtak.data.overlays.MissionOverlay
import com.airtak.data.geocoding.GeocodingResult
import com.airtak.data.overlays.MissionOverlayCornerId
import com.airtak.data.overlays.MissionOverlayCorners
import com.airtak.data.overlays.MissionOverlayPoint
import com.airtak.data.routing.RouteCalcState
import com.airtak.data.routing.SegmentDownloadState
import com.airtak.proto.MarkerType
import com.airtak.proto.PlayerStatus
import com.airtak.proto.Scope
import com.airtak.proto.TeamControl
import com.airtak.ui.theme.AccentAmber
import com.airtak.ui.theme.AccentCyan
import com.airtak.ui.theme.DarkBackground
import com.airtak.ui.theme.LightBackground
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.rasterOpacity
import org.maplibre.android.style.layers.PropertyFactory.symbolPlacement
import org.maplibre.android.style.layers.PropertyFactory.symbolSpacing
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.textKeepUpright
import org.maplibre.android.style.layers.PropertyFactory.textMaxAngle
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textPitchAlignment
import org.maplibre.android.style.layers.PropertyFactory.textRotationAlignment
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.ImageSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

@Composable
fun MapScreen(
    viewModel: MapViewModel = hiltViewModel(),
    focusNodeId: Long? = null,
    onFocusConsumed: () -> Unit = {},
    focusMarkerId: Long? = null,
    onMarkerFocusConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val ui by viewModel.uiState.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val phoneGpsActive by viewModel.phoneGpsActive.collectAsState()
    val phoneGpsProvidersEnabled by viewModel.phoneGpsProvidersEnabled.collectAsState()
    val phoneGpsFix by viewModel.phoneGpsFix.collectAsState()
    val gpsStartFailure by viewModel.gpsStartFailure.collectAsState()
    val isDark = MaterialTheme.colorScheme.background == DarkBackground
    val bgColor = (if (isDark) DarkBackground else LightBackground).toArgb()
    val textColor = MaterialTheme.colorScheme.onBackground.toArgb()

    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var centeredOnce by remember { mutableStateOf(false) }
    var pendingWaypoint by remember { mutableStateOf<LatLng?>(null) }
    var waypointLabel by remember { mutableStateOf("") }
    var tileSelectMode by remember { mutableStateOf(false) }
    var tileFirstCorner by remember { mutableStateOf<LatLng?>(null) }
    var selectedTileBounds by remember { mutableStateOf<TileBounds?>(null) }
    var zoomRange by remember { mutableStateOf(6f..19f) }
    var tileUrl by remember { mutableStateOf("") }
    var ownLabelPoint by remember { mutableStateOf<Offset?>(null) }
    var missionOverlayEditMode by remember { mutableStateOf(false) }
    var selectedOverlayCorner by remember { mutableStateOf(MissionOverlayCornerId.TOP_LEFT) }
    var overlayCornerPoints by remember { mutableStateOf<Map<MissionOverlayCornerId, Offset>>(emptyMap()) }
    var showSearch by remember { mutableStateOf(false) }
    val markers by viewModel.markers.collectAsState()
    val drawing by viewModel.drawing.collectAsState()
    val gameActive by viewModel.gameActive.collectAsState()
    val playerStatus by viewModel.playerStatus.collectAsState()
    val shareEnabled by viewModel.overlayShareEnabled.collectAsState()
    val availableAlignments by viewModel.availableAlignments.collectAsState()
    var pendingCommit by remember { mutableStateOf<MarkerTool?>(null) }
    var tappedMarker by remember { mutableStateOf<MarkerVisual?>(null) }
    var editingMarker by remember { mutableStateOf<MarkerVisual?>(null) }
    var showAlignmentPicker by remember { mutableStateOf(false) }
    var pendingAlignmentImport by remember { mutableStateOf<com.airtak.data.overlays.ReceivedAlignment?>(null) }
    var showStatusPicker by remember { mutableStateOf(false) }
    val routeState by viewModel.routeState.collectAsState()
    val selectedProfileName by viewModel.selectedProfile.collectAsState()
    var segmentSelectMode by remember { mutableStateOf(false) }
    var segmentFirstCorner by remember { mutableStateOf<LatLng?>(null) }
    var selectedSegmentBounds by remember { mutableStateOf<TileBounds?>(null) }
    var routePlanMode by remember { mutableStateOf(false) }
    var routePlanStart by remember { mutableStateOf<LatLng?>(null) }
    var routePlanEnd by remember { mutableStateOf<LatLng?>(null) }

    fun updateOwnLabelPoint() {
        val pos = ui.ownPosition
        val targetMap = map
        ownLabelPoint = if (pos != null && targetMap != null) {
            val screen = targetMap.projection.toScreenLocation(LatLng(pos.latitude, pos.longitude))
            Offset(screen.x, screen.y)
        } else {
            null
        }
    }

    fun updateOverlayCornerPoints() {
        val overlay = ui.missionOverlay
        val targetMap = map
        overlayCornerPoints = if (overlay != null && targetMap != null) {
            overlay.corners.toScreenPoints(targetMap)
        } else {
            emptyMap()
        }
    }

    fun moveOverlayCornerToScreen(cornerId: MissionOverlayCornerId, screenPoint: Offset) {
        val targetMap = map ?: return
        val latLng = targetMap.projection.fromScreenLocation(PointF(screenPoint.x, screenPoint.y))
        selectedOverlayCorner = cornerId
        viewModel.moveMissionOverlayCorner(cornerId, latLng.latitude, latLng.longitude)
        overlayCornerPoints = overlayCornerPoints.toMutableMap().apply { put(cornerId, screenPoint) }
    }

    fun nudgeOverlayCorner(cornerId: MissionOverlayCornerId, dx: Float, dy: Float) {
        val current = overlayCornerPoints[cornerId] ?: return
        moveOverlayCornerToScreen(cornerId, current + Offset(dx, dy))
    }

    fun markTileCorner(latLng: LatLng) {
        val first = tileFirstCorner
        if (first == null) {
            tileFirstCorner = latLng
        } else {
            selectedTileBounds = boundsFromCorners(first, latLng)
            tileFirstCorner = null
            tileSelectMode = false
        }
    }

    fun markSegmentCorner(latLng: LatLng) {
        val first = segmentFirstCorner
        if (first == null) {
            segmentFirstCorner = latLng
        } else {
            selectedSegmentBounds = boundsFromCorners(first, latLng)
            segmentFirstCorner = null
            segmentSelectMode = false
        }
    }

    fun markRoutePlanPoint(latLng: LatLng) {
        if (routePlanStart == null) {
            routePlanStart = latLng
        } else {
            routePlanEnd = latLng
            routePlanMode = false
            val start = routePlanStart!!
            val end = latLng
            viewModel.navigateRoutePoints(start.latitude, start.longitude, end.latitude, end.longitude)
            routePlanStart = null
            routePlanEnd = null
        }
    }

    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply {
            getMapAsync { readyMap ->
                map = readyMap
                readyMap.uiSettings.isRotateGesturesEnabled = false
                readyMap.uiSettings.isTiltGesturesEnabled = false
                readyMap.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(0.0, 0.0))
                    .zoom(2.0)
                    .bearing(0.0)
                    .tilt(0.0)
                    .build()
            }
        }
    }

    val overlayPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val targetMap = map
        if (uri != null && targetMap != null) {
            viewModel.importMissionOverlay(uri, initialMissionOverlayCorners(targetMap, mapView))
            missionOverlayEditMode = true
            selectedOverlayCorner = MissionOverlayCornerId.TOP_LEFT
        }
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        mapView.onCreate(null)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    DisposableEffect(map, tileSelectMode, tileFirstCorner, segmentSelectMode, segmentFirstCorner, routePlanMode, routePlanStart, routePlanEnd, missionOverlayEditMode, selectedOverlayCorner, ui.missionOverlay, drawing) {
        val targetMap = map
        if (targetMap == null) {
            onDispose {}
        } else {
            val clickListener = MapLibreMap.OnMapClickListener { latLng ->
                if (missionOverlayEditMode && ui.missionOverlay != null) {
                    true
                } else if (tileSelectMode) {
                    markTileCorner(latLng)
                    true
                } else if (segmentSelectMode) {
                    markSegmentCorner(latLng)
                    true
                } else if (routePlanMode) {
                    markRoutePlanPoint(latLng)
                    true
                } else if (drawing is DrawingState.Active) {
                    viewModel.addDrawingPoint(latLng.latitude, latLng.longitude)
                    true
                } else {
                    // Hit-test marker layers — surface tapped marker in detail sheet.
                    val screen = targetMap.projection.toScreenLocation(latLng)
                    val features = targetMap.queryRenderedFeatures(
                        screen,
                        MARKER_POINT_LAYER,
                        MARKER_POINT_LABEL_LAYER,
                        MARKER_ROUTE_LINE_LAYER,
                        MARKER_ROUTE_LABEL_LAYER,
                        MARKER_ROUTE_ARROW_LAYER,
                        MARKER_ROUTE_END_ARROW_LAYER,
                        MARKER_AREA_FILL_LAYER,
                        MARKER_AREA_LABEL_LAYER,
                    )
                    val markerIdStr = features.firstOrNull()?.getStringProperty("markerId")
                    val mid = markerIdStr?.toLongOrNull()
                    if (mid != null) {
                        tappedMarker = viewModel.markers.value.firstOrNull { it.markerId == mid }
                        true
                    } else {
                        false
                    }
                }
            }
            val longClickListener = MapLibreMap.OnMapLongClickListener { latLng ->
                if (missionOverlayEditMode && ui.missionOverlay != null) {
                    // Corner alignment is handled by draggable screen-space handles.
                } else if (tileSelectMode) {
                    markTileCorner(latLng)
                } else if (segmentSelectMode) {
                    markSegmentCorner(latLng)
                } else if (routePlanMode) {
                    markRoutePlanPoint(latLng)
                } else if (drawing is DrawingState.Active) {
                    viewModel.addDrawingPoint(latLng.latitude, latLng.longitude)
                } else {
                    // Long-press on a marker → edit (if locally owned) or detail.
                    val screen = targetMap.projection.toScreenLocation(latLng)
                    val features = targetMap.queryRenderedFeatures(
                        screen,
                        MARKER_POINT_LAYER,
                        MARKER_POINT_LABEL_LAYER,
                        MARKER_ROUTE_LINE_LAYER,
                        MARKER_ROUTE_LABEL_LAYER,
                        MARKER_ROUTE_ARROW_LAYER,
                        MARKER_ROUTE_END_ARROW_LAYER,
                        MARKER_AREA_FILL_LAYER,
                        MARKER_AREA_LABEL_LAYER,
                    )
                    val mid = features.firstOrNull()?.getStringProperty("markerId")?.toLongOrNull()
                    val hit = mid?.let { id -> viewModel.markers.value.firstOrNull { it.markerId == id } }
                    if (hit != null) {
                        if (hit.ownedLocally) editingMarker = hit else tappedMarker = hit
                    } else {
                        pendingWaypoint = latLng
                        waypointLabel = ""
                    }
                }
                true
            }
            targetMap.addOnMapClickListener(clickListener)
            targetMap.addOnMapLongClickListener(longClickListener)
            onDispose {
                targetMap.removeOnMapClickListener(clickListener)
                targetMap.removeOnMapLongClickListener(longClickListener)
            }
        }
    }

    DisposableEffect(map, ui.ownPosition) {
        val targetMap = map
        if (targetMap == null || ui.ownPosition == null) {
            ownLabelPoint = null
            onDispose {}
        } else {
            val moveListener = MapLibreMap.OnCameraMoveListener { updateOwnLabelPoint() }
            val idleListener = MapLibreMap.OnCameraIdleListener { updateOwnLabelPoint() }
            targetMap.addOnCameraMoveListener(moveListener)
            targetMap.addOnCameraIdleListener(idleListener)
            updateOwnLabelPoint()
            onDispose {
                targetMap.removeOnCameraMoveListener(moveListener)
                targetMap.removeOnCameraIdleListener(idleListener)
            }
        }
    }

    DisposableEffect(map, ui.missionOverlay, missionOverlayEditMode) {
        val targetMap = map
        if (targetMap == null || ui.missionOverlay == null || !missionOverlayEditMode) {
            overlayCornerPoints = emptyMap()
            onDispose {}
        } else {
            val moveListener = MapLibreMap.OnCameraMoveListener { updateOverlayCornerPoints() }
            val idleListener = MapLibreMap.OnCameraIdleListener { updateOverlayCornerPoints() }
            targetMap.addOnCameraMoveListener(moveListener)
            targetMap.addOnCameraIdleListener(idleListener)
            updateOverlayCornerPoints()
            onDispose {
                targetMap.removeOnCameraMoveListener(moveListener)
                targetMap.removeOnCameraIdleListener(idleListener)
            }
        }
    }

    LaunchedEffect(map, ui.missionOverlay, missionOverlayEditMode) {
        if (missionOverlayEditMode) updateOverlayCornerPoints()
    }

    LaunchedEffect(map, bgColor, ui.tileTemplate, ui.localTileTemplate, ui.localTileMaxZoom, textColor, isDark) {
        map?.setStyle(Style.Builder().fromJson(mapStyleJson(bgColor, ui.tileTemplate, ui.localTileTemplate, ui.localTileMaxZoom, isDark))) { style ->
            ensureLayers(style, textColor)
            updateMissionOverlay(style, ui.missionOverlay)
            updateMissionOverlayEditLayer(style, ui.missionOverlay, missionOverlayEditMode)
            updateLayers(style, ui)
            updateTileSelectionLayer(style, selectedTileBounds)
        }
    }

    LaunchedEffect(map, ui, missionOverlayEditMode) {
        map?.getStyle { style ->
            updateMissionOverlay(style, ui.missionOverlay)
            updateMissionOverlayEditLayer(style, ui.missionOverlay, missionOverlayEditMode)
            updateLayers(style, ui)
        }
    }

    LaunchedEffect(map, markers) {
        map?.getStyle { style -> updateMarkerLayers(style, markers) }
    }
    LaunchedEffect(map, drawing) {
        map?.getStyle { style -> updateDraftLayer(style, drawing) }
    }
    LaunchedEffect(map, routeState) {
        map?.getStyle { style -> updateNavRouteLayer(style, routeState) }
    }

    LaunchedEffect(map, tileFirstCorner, selectedTileBounds) {
        map?.getStyle { style -> updateTileSelectionLayer(style, selectedTileBounds) }
    }

    // Keyed on `map` as well as on the position. getMapAsync is asynchronous, and the previous
    // version consumed `centeredOnce` while `map` was still null: the camera call no-oped, the flag
    // was spent, and nothing re-ran, so the map stayed on the world-view default.
    LaunchedEffect(map, ui.ownPosition) {
        val readyMap = map ?: return@LaunchedEffect
        val pos = ui.ownPosition ?: return@LaunchedEffect
        if (!centeredOnce) {
            centeredOnce = true
            readyMap.animateCamera(northUpCamera(LatLng(pos.latitude, pos.longitude), MAP_SELF_ZOOM))
        }
    }

    // Cross-screen locate deep-link: fly to a specific node when requested.
    val focusTarget by viewModel.focusTarget.collectAsState()
    LaunchedEffect(focusNodeId) {
        if (focusNodeId != null) {
            viewModel.focusNode(focusNodeId)
            onFocusConsumed()
        }
    }
    LaunchedEffect(focusMarkerId, markers) {
        if (focusMarkerId != null && markers.any { it.markerId == focusMarkerId }) {
            viewModel.focusMarker(focusMarkerId)
            onMarkerFocusConsumed()
        }
    }
    LaunchedEffect(map, focusTarget) {
        val target = focusTarget ?: return@LaunchedEffect
        if (map != null) {
            map?.animateCamera(northUpCamera(LatLng(target.latitude, target.longitude), MAP_LOCATE_ZOOM))
            viewModel.clearFocus()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        MapToolbar(
            showPlayers = ui.showPlayers,
            showWaypoints = ui.showWaypoints,
            selectingTiles = tileSelectMode,
            selectingSegments = segmentSelectMode,
            routePlanning = routePlanMode,
            gameActive = gameActive,
            drawingActive = drawing is DrawingState.Active,
            playerStatus = playerStatus,
            onPickMarkerTool = { tool -> viewModel.startDrawing(tool) },
            onToggleGameActive = { viewModel.setGameActive(!gameActive) },
            onTogglePlayers = { viewModel.setShowPlayers(!ui.showPlayers) },
            onToggleWaypoints = { viewModel.setShowWaypoints(!ui.showWaypoints) },
            onSearch = { showSearch = true },
            onToggleStatusPicker = { showStatusPicker = true },
            hasMissionOverlay = ui.missionOverlay != null,
            editingMissionOverlay = missionOverlayEditMode,
            onMissionOverlay = {
                if (ui.missionOverlay == null) {
                    overlayPicker.launch(MISSION_OVERLAY_IMAGE_MIME)
                } else {
                    missionOverlayEditMode = !missionOverlayEditMode
                }
            },
            onDownload = {
                tileSelectMode = !tileSelectMode
                missionOverlayEditMode = false
                segmentSelectMode = false
                routePlanMode = false
                tileFirstCorner = null
                selectedTileBounds = null
                tileUrl = ui.configuredTileTemplate
            },
            onSegmentDownload = {
                segmentSelectMode = !segmentSelectMode
                missionOverlayEditMode = false
                tileSelectMode = false
                routePlanMode = false
                segmentFirstCorner = null
                selectedSegmentBounds = null
            },
            onPlanRoute = {
                routePlanMode = !routePlanMode
                missionOverlayEditMode = false
                tileSelectMode = false
                segmentSelectMode = false
                routePlanStart = null
                routePlanEnd = null
            },
            selectedProfile = selectedProfileName,
            availableProfiles = viewModel.availableProfiles,
            onSelectProfile = { viewModel.setProfile(it) },
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
        )

        val missionOverlay = ui.missionOverlay
        if (missionOverlayEditMode && missionOverlay != null) {
            MissionOverlayControls(
                selectedCorner = selectedOverlayCorner,
                opacity = missionOverlay.opacity,
                shareEnabled = shareEnabled,
                hasAlignments = availableAlignments.isNotEmpty(),
                onOpacityChange = viewModel::setMissionOverlayOpacity,
                onNudge = { dx, dy -> nudgeOverlayCorner(selectedOverlayCorner, dx, dy) },
                onShareToggle = { viewModel.setOverlayShareEnabled(it) },
                onRebroadcast = { viewModel.broadcastOverlayAlignment() },
                onImport = { showAlignmentPicker = true },
                onReplace = { overlayPicker.launch(MISSION_OVERLAY_IMAGE_MIME) },
                onRemove = {
                    viewModel.removeMissionOverlay()
                    missionOverlayEditMode = false
                },
                onDone = { missionOverlayEditMode = false },
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
            )

            MissionOverlayDragHandles(
                points = overlayCornerPoints,
                selectedCorner = selectedOverlayCorner,
                onSelectedCorner = { selectedOverlayCorner = it },
                onDragCorner = ::moveOverlayCornerToScreen,
            )
        }

        TileStatus(
            state = ui.tileDownload,
            tileTemplate = ui.localTileTemplate,
            localTileMaxZoom = ui.localTileMaxZoom,
            selectingTiles = tileSelectMode,
            firstCorner = tileFirstCorner != null,
            modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
        )

        if (segmentSelectMode) {
            StatusPill(
                text = if (segmentFirstCorner != null) "Routing region: second corner" else "Routing region: first corner",
                color = AccentCyan,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 56.dp),
            )
        }
        val segState by viewModel.segmentDownloadState.collectAsState()
        val segVal = segState
        when (segVal) {
            SegmentDownloadState.Idle -> {}
            is SegmentDownloadState.Running -> {
                StatusPill(
                    "Segments ${segVal.completed}/${segVal.total}",
                    AccentCyan,
                    Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 56.dp),
                )
            }
            is SegmentDownloadState.Complete -> {
                val total = segVal.downloaded + segVal.skipped
                if (total == 0) {
                    StatusPill("No segments downloaded", AccentAmber, Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 56.dp))
                } else {
                    StatusPill("Routing data ready ($total segments)", AccentCyan, Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 56.dp))
                }
            }
            is SegmentDownloadState.Failed -> {
                StatusPill(segVal.message, AccentAmber, Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 56.dp))
            }
        }

        if (routePlanMode) {
            StatusPill(
                text = if (routePlanStart == null) "Route: tap start point" else "Route: tap end point",
                color = AccentCyan,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 96.dp),
            )
        }
        val routeCalc = routeState
        if (routeCalc is RouteCalcState.Calculating) {
            StatusPill("Calculating route...", AccentCyan, Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 96.dp))
        }
        if (routeCalc is RouteCalcState.Failed) {
            StatusPill(
                routeCalc.message,
                AccentAmber,
                Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 96.dp),
            )
            LaunchedEffect(routeCalc) {
                kotlinx.coroutines.delay(MAP_ROUTE_ERROR_DISMISS_MS)
                if (routeState is RouteCalcState.Failed) viewModel.clearRoute()
            }
        }

        if (routeState is RouteCalcState.Complete) {
            val state = routeState as RouteCalcState.Complete
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        formatDistance(state.distanceM),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = { viewModel.clearRoute() }) {
                        Text(stringResource(R.string.action_clear))
                    }
                }
            }
        }

        val ownLabel = ui.ownPosition?.label.orEmpty()
        val labelPoint = ownLabelPoint
        if (ownLabel.isNotBlank() && labelPoint != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset {
                        IntOffset(
                            x = labelPoint.x.roundToInt() - 80,
                            y = labelPoint.y.roundToInt() - 46,
                        )
                    }
                    .width(160.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = ownLabel,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // A listener that failed to bind used to look exactly like an app with no tiles to serve.
        TileServerProblemBanner(
            reason = ui.tileServerProblem,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        FloatingActionButton(
            onClick = {
                val readyMap = map
                val pos = ui.ownPosition
                if (readyMap == null || pos == null) {
                    // An enabled button that does nothing is indistinguishable from a broken
                    // camera; say which of the two it is.
                    Toast.makeText(
                        context,
                        noPositionReason(
                            registered = phoneGpsActive,
                            providersEnabled = phoneGpsProvidersEnabled,
                            hasFix = phoneGpsFix,
                            failure = gpsStartFailure,
                        ),
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@FloatingActionButton
                }
                readyMap.animateCamera(northUpCamera(LatLng(pos.latitude, pos.longitude), MAP_SELF_ZOOM))
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .size(56.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Icon(Icons.Outlined.MyLocation, contentDescription = stringResource(R.string.map_cd_recenter))
        }

        MarkerDrawingToolbar(
            drawing = drawing,
            onCancel = { viewModel.cancelDrawing() },
            onUndo = { viewModel.removeLastDrawingPoint() },
            onCommit = {
                val active = drawing as? DrawingState.Active ?: return@MarkerDrawingToolbar
                if (viewModel.canCommitDrawing()) pendingCommit = active.tool
            },
            canCommit = viewModel.canCommitDrawing(),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp),
        )
    }

    pendingWaypoint?.let { latLng ->
        AlertDialog(
            onDismissRequest = { pendingWaypoint = null },
            title = { Text(stringResource(R.string.map_waypoint_dialog_title)) },
            text = {
                OutlinedTextField(
                    value = waypointLabel,
                    onValueChange = { waypointLabel = it.take(24) },
                    label = { Text(stringResource(R.string.common_label)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.addWaypoint(latLng.latitude, latLng.longitude, waypointLabel)
                    pendingWaypoint = null
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingWaypoint = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    pendingCommit?.let { tool ->
        val active = drawing as? DrawingState.Active
        if (active == null || active.tool != tool) {
            pendingCommit = null
        } else {
            MarkerCommitDialog(
                tool = tool,
                pointCount = active.points.size,
                onDismiss = { pendingCommit = null },
                onCommit = { label, description, color, scope, team, priority ->
                    viewModel.commitDrawing(label, description, color, scope, team, priority)
                    pendingCommit = null
                },
            )
        }
    }

    tappedMarker?.let { marker ->
        MarkerDetailDialog(
            marker = marker,
            ownPosition = ui.ownPosition,
            onDismiss = { tappedMarker = null },
            onDelete = {
                viewModel.deleteMarker(marker.markerId)
                tappedMarker = null
            },
            onNavigate = {
                val own = ui.ownPosition ?: return@MarkerDetailDialog
                viewModel.navigateToMarker(marker, own.latitude, own.longitude)
            },
        )
    }

    editingMarker?.let { marker ->
        MarkerEditDialog(
            marker = marker,
            onDismiss = { editingMarker = null },
            onDelete = {
                viewModel.deleteMarker(marker.markerId)
                editingMarker = null
            },
            onSave = { label, description, color, scope, team, priority ->
                viewModel.updateMarker(
                    markerId = marker.markerId,
                    label = label,
                    description = description,
                    colorRgb = color,
                    scopeKind = scope,
                    controllingTeam = team,
                    priority = priority,
                )
                editingMarker = null
            },
        )
    }

    selectedTileBounds?.let { bounds ->
        val minZoom = zoomRange.start.toInt()
        val maxZoom = zoomRange.endInclusive.toInt()
        val tileCount = remember(bounds, minZoom, maxZoom) { estimateTileCount(bounds, minZoom, maxZoom) }
        val tileUrlValid = tileUrl.contains("{z}") && tileUrl.contains("{x}") && tileUrl.contains("{y}")
        val tooManyTiles = tileCount > MAP_MAX_TILES_PER_DOWNLOAD
        AlertDialog(
            onDismissRequest = { selectedTileBounds = null },
            title = { Text(stringResource(R.string.map_tile_region_title)) },
            text = {
                androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        bounds.displayString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Zoom $minZoom-$maxZoom · $tileCount tiles",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    RangeSlider(
                        value = zoomRange,
                        onValueChange = { range ->
                            val start = range.start.toInt().coerceIn(6, 19).toFloat()
                            val end = range.endInclusive.toInt().coerceIn(start.toInt(), 19).toFloat()
                            zoomRange = start..end
                        },
                        valueRange = 6f..19f,
                        steps = 12,
                    )
                    OutlinedTextField(
                        value = tileUrl,
                        onValueChange = { tileUrl = it.take(UI_TILE_URL_MAX_LEN) },
                        label = { Text(stringResource(R.string.map_tile_url_field)) },
                        placeholder = { Text(stringResource(R.string.map_tile_url_placeholder)) },
                        singleLine = true,
                    )
                    if (!tileUrlValid) {
                        Text(
                            "URL must include {z}, {x}, and {y}.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else if (tooManyTiles) {
                        Text(
                            "Selected area is too large; narrow the region or zoom range.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = tileUrlValid && !tooManyTiles,
                    onClick = {
                        viewModel.downloadVisible(bounds, minZoom, maxZoom, tileUrl.trim())
                        selectedTileBounds = null
                    },
                ) { Text(stringResource(R.string.action_download)) }
            },
            dismissButton = {
                TextButton(onClick = { selectedTileBounds = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    selectedSegmentBounds?.let { bounds ->
        AlertDialog(
            onDismissRequest = { selectedSegmentBounds = null },
            title = { Text(stringResource(R.string.map_routing_region_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        bounds.displayString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Download BRouter .rd5 segment files for this area to enable on-road/trail route calculation.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Without segments → fallback to straight-line route.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.downloadSegments(bounds)
                    selectedSegmentBounds = null
                }) { Text(stringResource(R.string.action_download)) }
            },
            dismissButton = {
                TextButton(onClick = { selectedSegmentBounds = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    if (showStatusPicker) {
        StatusPickerDialog(
            selected = playerStatus,
            onSelect = { status ->
                viewModel.setPlayerStatus(status)
                showStatusPicker = false
            },
            onDismiss = { showStatusPicker = false },
        )
    }

    if (showSearch) {
        SearchDialog(
            query = searchQuery,
            results = searchResults,
            isSearching = isSearching,
            onQueryChange = { viewModel.setSearchQuery(it) },
            onSearch = { viewModel.searchLocations(searchQuery) },
            onSelectResult = { result ->
                viewModel.selectSearchResult(result)
                showSearch = false
            },
            onDismiss = {
                viewModel.clearSearch()
                showSearch = false
            },
        )
    }

    val alignmentImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val alignment = pendingAlignmentImport ?: return@rememberLauncherForActivityResult
        pendingAlignmentImport = null
        if (uri != null) {
            viewModel.importOverlayAlignment(alignment, uri)
        }
    }

    if (showAlignmentPicker) {
        AlignmentPickerDialog(
            alignments = availableAlignments,
            onDismiss = { showAlignmentPicker = false },
            onSelect = { alignment ->
                showAlignmentPicker = false
                pendingAlignmentImport = alignment
                alignmentImagePicker.launch(MISSION_OVERLAY_IMAGE_MIME)
            },
        )
    }
}

@Composable
private fun AlignmentPickerDialog(
    alignments: List<com.airtak.data.overlays.ReceivedAlignment>,
    onDismiss: () -> Unit,
    onSelect: (com.airtak.data.overlays.ReceivedAlignment) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.map_import_alignment_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (alignments.isEmpty()) {
                    Text(stringResource(R.string.map_no_alignments), style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(stringResource(R.string.map_import_alignment_hint),
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    alignments.sortedByDescending { it.timestampUnix }.forEach { a ->
                        val label = stringResource(
                            R.string.map_alignment_row,
                            a.sharerNodeId.toString(16).uppercase().take(8),
                            a.imageFileName,
                        )
                        TextButton(onClick = { onSelect(a) }, modifier = Modifier.fillMaxWidth()) {
                            Text(label, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun SearchDialog(
    query: String,
    results: List<GeocodingResult>,
    isSearching: Boolean,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSelectResult: (GeocodingResult) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.map_search_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        label = { Text(stringResource(R.string.map_search_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = onSearch, enabled = query.isNotBlank() && !isSearching) {
                        Text(stringResource(R.string.action_go))
                    }
                }
                if (isSearching) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxSize())
                }
                if (results.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                RoundedCornerShape(8.dp),
                            )
                            .padding(4.dp),
                    ) {
                        results.forEach { result ->
                            TextButton(
                                onClick = { onSelectResult(result) },
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                Text(
                                    text = result.displayName,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

@Composable
private fun StatusPickerDialog(
    selected: PlayerStatus,
    onSelect: (PlayerStatus) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.map_player_status_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Changes are broadcast to the mesh and visible to other players.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                listOf(
                    PlayerStatus.PLAYER_STATUS_ACTIVE,
                    PlayerStatus.PLAYER_STATUS_INACTIVE,
                    PlayerStatus.PLAYER_STATUS_HIT,
                    PlayerStatus.PLAYER_STATUS_RESPAWNING,
                    PlayerStatus.PLAYER_STATUS_ELIMINATED,
                ).forEach { status ->
                    val isSelected = selected == status
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(status) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            color = statusColor(status).copy(alpha = if (isSelected) 0.30f else 0.10f),
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Text(
                                statusShortLabel(status),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = statusColor(status),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            statusDescription(status),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

@Composable
private fun MapToolbar(
    showPlayers: Boolean,
    showWaypoints: Boolean,
    selectingTiles: Boolean,
    selectingSegments: Boolean,
    routePlanning: Boolean,
    gameActive: Boolean,
    drawingActive: Boolean,
    playerStatus: PlayerStatus,
    onPickMarkerTool: (MarkerTool) -> Unit,
    onToggleGameActive: () -> Unit,
    onTogglePlayers: () -> Unit,
    onToggleWaypoints: () -> Unit,
    onSearch: () -> Unit,
    onToggleStatusPicker: () -> Unit,
    hasMissionOverlay: Boolean,
    editingMissionOverlay: Boolean,
    onMissionOverlay: () -> Unit,
    onDownload: () -> Unit,
    onSegmentDownload: () -> Unit,
    onPlanRoute: () -> Unit,
    selectedProfile: String,
    availableProfiles: List<String>,
    onSelectProfile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 6.dp, vertical = 4.dp)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            IconButton(
                onClick = onSearch,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) { Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.map_cd_search)) }
            var markerMenuExpanded by remember { mutableStateOf(false) }
            Box {
                IconButton(
                    onClick = { markerMenuExpanded = true },
                    enabled = !drawingActive,
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = if (drawingActive) AccentAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) { Icon(Icons.Outlined.AddLocationAlt, contentDescription = stringResource(R.string.map_cd_add_marker)) }
                DropdownMenu(
                    expanded = markerMenuExpanded,
                    onDismissRequest = { markerMenuExpanded = false },
                ) {
                    MarkerTool.values().forEach { tool ->
                        DropdownMenuItem(
                            text = { Text(tool.uiLabel()) },
                            onClick = {
                                markerMenuExpanded = false
                                onPickMarkerTool(tool)
                            },
                        )
                    }
                }
            }
            IconButton(
                onClick = onToggleGameActive,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = if (gameActive) AccentAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                if (gameActive) {
                    Icon(Icons.Outlined.Pause, contentDescription = stringResource(R.string.map_cd_end_game))
                } else {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = stringResource(R.string.map_cd_resume_game))
                }
            }
            Surface(
                modifier = Modifier.clickable { onToggleStatusPicker() },
                color = statusColor(playerStatus).copy(alpha = 0.16f),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    statusShortLabel(playerStatus),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = statusColor(playerStatus),
                )
            }
            IconButton(
                onClick = onTogglePlayers,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = if (showPlayers) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) { Icon(Icons.Outlined.Groups, contentDescription = stringResource(R.string.map_cd_players)) }
            IconButton(
                onClick = onToggleWaypoints,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = if (showWaypoints) AccentAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) { Icon(Icons.Outlined.Place, contentDescription = stringResource(R.string.map_cd_waypoints)) }
            IconButton(
                onClick = onMissionOverlay,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = if (editingMissionOverlay || hasMissionOverlay) AccentCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) { Icon(Icons.Outlined.Image, contentDescription = stringResource(R.string.map_cd_mission_overlay)) }
            IconButton(
                onClick = onDownload,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = if (selectingTiles) AccentCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Icon(Icons.Outlined.Download, contentDescription = stringResource(R.string.map_cd_download_tiles))
            }
            IconButton(
                onClick = onSegmentDownload,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = if (selectingSegments) AccentCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Icon(Icons.Outlined.Layers, contentDescription = stringResource(R.string.map_cd_download_routing))
            }
            IconButton(
                onClick = onPlanRoute,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = if (routePlanning) AccentCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Icon(Icons.AutoMirrored.Outlined.AltRoute, contentDescription = stringResource(R.string.map_cd_plan_route))
            }
            Box {
                var profileExpanded by remember { mutableStateOf(false) }
                Surface(
                    modifier = Modifier.clickable { profileExpanded = true },
                    shape = RoundedCornerShape(8.dp),
                    color = AccentCyan.copy(alpha = 0.12f),
                ) {
                    Text(
                        selectedProfile.replace("-", " "),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = AccentCyan,
                    )
                }
                DropdownMenu(
                    expanded = profileExpanded,
                    onDismissRequest = { profileExpanded = false },
                ) {
                    availableProfiles.forEach { name ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    name.replace("-", " "),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                            onClick = {
                                onSelectProfile(name)
                                profileExpanded = false
                            },
                        )
                    }
                }
            }
        }
    }
}

// ── Tactical marker drawing UI ──────────────────────────────────────────────

@Composable
private fun MarkerDrawingToolbar(
    drawing: DrawingState,
    onCancel: () -> Unit,
    onUndo: () -> Unit,
    onCommit: () -> Unit,
    canCommit: Boolean,
    modifier: Modifier = Modifier,
) {
    if (drawing !is DrawingState.Active) return
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                stringResource(R.string.map_points_placed, drawing.tool.uiLabel(), drawing.points.size),
                style = MaterialTheme.typography.labelMedium,
            )
            val instructions = when (drawing.tool) {
                MarkerTool.POINT, MarkerTool.NOTE, MarkerTool.OBJECTIVE -> stringResource(R.string.map_draw_hint_single)
                MarkerTool.ROUTE -> stringResource(R.string.map_draw_hint_route, drawing.tool.maxPoints)
                MarkerTool.AREA  -> stringResource(R.string.map_draw_hint_area, drawing.tool.maxPoints)
            }
            Text(
                instructions,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
                if (drawing.tool == MarkerTool.ROUTE || drawing.tool == MarkerTool.AREA) {
                    TextButton(
                        onClick = onUndo,
                        enabled = drawing.points.isNotEmpty(),
                    ) { Text(stringResource(R.string.action_undo)) }
                }
                Button(onClick = onCommit, enabled = canCommit) { Text(stringResource(R.string.action_commit)) }
            }
        }
    }
}

private val MARKER_COLOR_PRESETS: List<Int> = listOf(
    0xE63946, 0xFFB300, 0x00E676, 0x1D9BF0, 0xB388FF, 0xFFFFFF,
).map { it.toInt() }

@Composable
private fun MarkerCommitDialog(
    tool: MarkerTool,
    pointCount: Int,
    onDismiss: () -> Unit,
    onCommit: (label: String, description: String, colorRgb: Int, scope: Scope, team: TeamControl, priority: Int) -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var colorRgb by remember { mutableStateOf(MARKER_COLOR_PRESETS.first()) }
    var scope by remember { mutableStateOf(Scope.SCOPE_ALL) }
    var team by remember { mutableStateOf(TeamControl.TEAM_CONTROL_UNKNOWN) }
    var priority by remember { mutableStateOf(2) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.map_new_marker_title, tool.uiLabel())) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "$pointCount point${if (pointCount == 1) "" else "s"} captured.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(32) },
                    label = { Text(stringResource(R.string.common_label)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(UI_MARKER_DESC_MAX_LEN) },
                    label = { Text(stringResource(R.string.common_description_optional)) },
                    minLines = 2,
                    maxLines = 4,
                )
                Text(stringResource(R.string.common_color), style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MARKER_COLOR_PRESETS.forEach { c ->
                        val selected = (colorRgb and UI_RGB_MASK) == c
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(androidx.compose.ui.graphics.Color(UI_ARGB_ALPHA_FULL or c.toLong()))
                                .clickable { colorRgb = c }
                                .padding(2.dp),
                        ) {
                            if (selected) {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(androidx.compose.ui.graphics.Color.Transparent),
                                ) {
                                    Text(stringResource(R.string.marker_check_glyph), color = androidx.compose.ui.graphics.Color.Black, modifier = Modifier.align(Alignment.Center))
                                }
                            }
                        }
                    }
                }
                Text(stringResource(R.string.common_visible_to), style = MaterialTheme.typography.labelSmall)
                ScopeDropdown(scope, onSelect = { scope = it })
                if (tool == MarkerTool.OBJECTIVE) {
                    Text(stringResource(R.string.common_controlling_team), style = MaterialTheme.typography.labelSmall)
                    TeamDropdown(team, onSelect = { team = it })
                    Text(stringResource(R.string.common_priority), style = MaterialTheme.typography.labelSmall)
                    PriorityDropdown(priority, onSelect = { priority = it })
                }
            }
        },
        confirmButton = {
            Button(onClick = { onCommit(label, description, colorRgb, scope, team, priority) }) {
                Text(stringResource(R.string.action_send))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun MarkerEditDialog(
    marker: MarkerVisual,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onSave: (label: String, description: String, colorRgb: Int, scope: Scope, team: TeamControl, priority: Int) -> Unit,
) {
    var label by remember(marker.markerId) { mutableStateOf(marker.label) }
    var description by remember(marker.markerId) { mutableStateOf(marker.description) }
    var colorRgb by remember(marker.markerId) { mutableStateOf(marker.colorRgb and UI_RGB_MASK) }
    var scope by remember(marker.markerId) { mutableStateOf(marker.scope) }
    var team by remember(marker.markerId) { mutableStateOf(marker.controllingTeam) }
    var priority by remember(marker.markerId) { mutableStateOf(marker.priority) }
    val isObjective = marker.type == MarkerType.MARKER_TYPE_OBJECTIVE
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.map_edit_marker_title, marker.type.labelText())) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(32) },
                    label = { Text(stringResource(R.string.common_label)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(UI_MARKER_DESC_MAX_LEN) },
                    label = { Text(stringResource(R.string.common_description_optional)) },
                    minLines = 2,
                    maxLines = 4,
                )
                Text(stringResource(R.string.common_color), style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MARKER_COLOR_PRESETS.forEach { c ->
                        val selected = colorRgb == c
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(androidx.compose.ui.graphics.Color(UI_ARGB_ALPHA_FULL or c.toLong()))
                                .clickable { colorRgb = c }
                                .padding(2.dp),
                        ) {
                            if (selected) {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(androidx.compose.ui.graphics.Color.Transparent),
                                ) {
                                    Text(stringResource(R.string.marker_check_glyph), color = androidx.compose.ui.graphics.Color.Black, modifier = Modifier.align(Alignment.Center))
                                }
                            }
                        }
                    }
                }
                Text(stringResource(R.string.common_visible_to), style = MaterialTheme.typography.labelSmall)
                ScopeDropdown(scope, onSelect = { scope = it })
                if (isObjective) {
                    Text(stringResource(R.string.common_controlling_team), style = MaterialTheme.typography.labelSmall)
                    TeamDropdown(team, onSelect = { team = it })
                    Text(stringResource(R.string.common_priority), style = MaterialTheme.typography.labelSmall)
                    PriorityDropdown(priority, onSelect = { priority = it })
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(label, description, colorRgb, scope, team, priority) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

@Composable
private fun ScopeDropdown(selected: Scope, onSelect: (Scope) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text(selected.labelText()) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf(Scope.SCOPE_ALL, Scope.SCOPE_COMPANY, Scope.SCOPE_PLATOON, Scope.SCOPE_SQUAD, Scope.SCOPE_FIRE_TEAM).forEach { s ->
                DropdownMenuItem(text = { Text(s.labelText()) }, onClick = { expanded = false; onSelect(s) })
            }
        }
    }
}

@Composable
private fun TeamDropdown(selected: TeamControl, onSelect: (TeamControl) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text(selected.labelText()) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf(
                TeamControl.TEAM_CONTROL_OWN,
                TeamControl.TEAM_CONTROL_OPPOSING,
                TeamControl.TEAM_CONTROL_NEUTRAL,
                TeamControl.TEAM_CONTROL_UNKNOWN,
            ).forEach { t ->
                DropdownMenuItem(text = { Text(t.labelText()) }, onClick = { expanded = false; onSelect(t) })
            }
        }
    }
}

@Composable
private fun PriorityDropdown(selected: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text(priorityText(selected)) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            (0..4).forEach { p ->
                DropdownMenuItem(text = { Text(priorityText(p)) }, onClick = { expanded = false; onSelect(p) })
            }
        }
    }
}

@Composable
private fun MarkerDetailDialog(
    marker: MarkerVisual,
    ownPosition: OwnPosition?,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onNavigate: () -> Unit = {},
) {
    val distance = ownPosition?.let { own ->
        marker.points.firstOrNull()?.let { point ->
            haversineDistance(own.latitude, own.longitude, point.first, point.second)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(marker.label.ifBlank { stringResource(R.string.marker_type_unknown) }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.map_detail_type, marker.type.labelText()), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.map_detail_scope, marker.scope.labelText()), style = MaterialTheme.typography.bodySmall)
                if (marker.type == MarkerType.MARKER_TYPE_OBJECTIVE) {
                    Text(stringResource(R.string.map_detail_team, marker.controllingTeam.labelText()), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.map_detail_priority, priorityText(marker.priority)), style = MaterialTheme.typography.bodySmall)
                }
                if (distance != null) {
                    Text(stringResource(R.string.map_detail_distance, formatDistance(distance)), style = MaterialTheme.typography.bodySmall)
                }
                if (marker.description.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(marker.description, style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "Creator: ${if (marker.ownedLocally) "you" else "%08X".format(marker.creatorNodeId)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = {
                    onNavigate()
                    onDismiss()
                }) {
                    Text(stringResource(R.string.map_navigate))
                }
                if (marker.ownedLocally) {
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
            }
        },
    )
}

private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val R = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return R * c
}

/** Distance with a unit. The unit strings are resources, so this reads them. */
@Composable
private fun formatDistance(meters: Double): String = when {
    meters < UI_DISTANCE_PRECISE_THRESHOLD_M ->
        stringResource(R.string.distance_meters, meters.roundToInt())
    meters < UI_DISTANCE_KM_THRESHOLD_M ->
        stringResource(R.string.distance_meters, (meters / 10).roundToInt() * 10)
    else -> stringResource(R.string.distance_km, meters / UI_METERS_TO_KM)
}

@Composable
private fun MissionOverlayControls(
    selectedCorner: MissionOverlayCornerId,
    opacity: Float,
    shareEnabled: Boolean,
    hasAlignments: Boolean,
    onOpacityChange: (Float) -> Unit,
    onNudge: (Float, Float) -> Unit,
    onShareToggle: (Boolean) -> Unit,
    onRebroadcast: () -> Unit,
    onImport: () -> Unit,
    onReplace: () -> Unit,
    onRemove: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.map_mission_overlay_title), style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.width(220.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(R.string.map_opacity), style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = opacity.coerceIn(0.1f, 1f),
                    onValueChange = onOpacityChange,
                    valueRange = 0.1f..1f,
                    modifier = Modifier.weight(1f),
                )
                Text(stringResource(R.string.percent_value, (opacity * 100f).roundToInt()), style = MaterialTheme.typography.labelSmall)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Checkbox(checked = shareEnabled, onCheckedChange = onShareToggle)
                Text(stringResource(R.string.map_share_alignment), style = MaterialTheme.typography.labelSmall)
                if (shareEnabled) {
                    IconButton(onClick = onRebroadcast) {
                        Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.map_cd_rebroadcast_alignment))
                    }
                }
                Spacer(Modifier.weight(1f))
                if (hasAlignments) {
                    TextButton(onClick = onImport) { Text(stringResource(R.string.action_import)) }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(stringResource(R.string.map_nudge_corner, selectedCorner.shortLabel()), style = MaterialTheme.typography.labelSmall)
                IconButton(onClick = { onNudge(0f, -MAP_NUDGE_PX) }) {
                    Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = stringResource(R.string.map_cd_nudge_up))
                }
                IconButton(onClick = { onNudge(-MAP_NUDGE_PX, 0f) }) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = stringResource(R.string.map_cd_nudge_left))
                }
                IconButton(onClick = { onNudge(MAP_NUDGE_PX, 0f) }) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = stringResource(R.string.map_cd_nudge_right))
                }
                IconButton(onClick = { onNudge(0f, MAP_NUDGE_PX) }) {
                    Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = stringResource(R.string.map_cd_nudge_down))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onReplace) { Text(stringResource(R.string.action_replace)) }
                TextButton(onClick = onRemove) { Text(stringResource(R.string.action_remove)) }
                Button(onClick = onDone) { Text(stringResource(R.string.action_done)) }
            }
        }
    }
}

@Composable
private fun MissionOverlayDragHandles(
    points: Map<MissionOverlayCornerId, Offset>,
    selectedCorner: MissionOverlayCornerId,
    onSelectedCorner: (MissionOverlayCornerId) -> Unit,
    onDragCorner: (MissionOverlayCornerId, Offset) -> Unit,
) {
    points.forEach { (corner, point) ->
        MissionOverlayDragHandle(
            corner = corner,
            point = point,
            selected = corner == selectedCorner,
            onSelectedCorner = onSelectedCorner,
            onDragCorner = onDragCorner,
        )
    }
}

@Composable
private fun MissionOverlayDragHandle(
    corner: MissionOverlayCornerId,
    point: Offset,
    selected: Boolean,
    onSelectedCorner: (MissionOverlayCornerId) -> Unit,
    onDragCorner: (MissionOverlayCornerId, Offset) -> Unit,
) {
    val latestPoint by rememberUpdatedState(point)
    val handleRadiusPx = with(LocalDensity.current) { 18.dp.toPx() }
    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    x = point.x.roundToInt() - handleRadiusPx.roundToInt(),
                    y = point.y.roundToInt() - handleRadiusPx.roundToInt(),
                )
            }
            .size(36.dp)
            .background(
                color = if (selected) AccentAmber else AccentCyan,
                shape = CircleShape,
            )
            .pointerInput(corner) {
                var currentPoint = latestPoint
                detectDragGestures(
                    onDragStart = {
                        currentPoint = latestPoint
                        onSelectedCorner(corner)
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        currentPoint += dragAmount
                        onDragCorner(corner, currentPoint)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = corner.shortLabel(),
            color = Color.Black,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

@Composable
private fun MissionOverlayCornerId.shortLabel(): String = stringResource(
    when (this) {
        MissionOverlayCornerId.TOP_LEFT -> R.string.corner_top_left
        MissionOverlayCornerId.TOP_RIGHT -> R.string.corner_top_right
        MissionOverlayCornerId.BOTTOM_RIGHT -> R.string.corner_bottom_right
        MissionOverlayCornerId.BOTTOM_LEFT -> R.string.corner_bottom_left
    },
)

@Composable
private fun TileStatus(
    state: TileDownloadState,
    tileTemplate: String?,
    localTileMaxZoom: Int?,
    selectingTiles: Boolean,
    firstCorner: Boolean,
    modifier: Modifier = Modifier,
) {
    if (selectingTiles) {
        StatusPill(
            text = if (firstCorner) "Tile region: second corner" else "Tile region: first corner",
            color = AccentCyan,
            modifier = modifier,
        )
        return
    }
    when (state) {
        TileDownloadState.Idle -> if (tileTemplate != null) {
            StatusPill(localTileMaxZoom?.let { "Field tiles to z$it" } ?: "Offline overview", AccentCyan, modifier)
        } else Unit
        is TileDownloadState.Running -> {
            Surface(
                modifier = modifier,
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = { if (state.total == 0) 0f else state.completed.toFloat() / state.total.toFloat() },
                        modifier = Modifier.width(120.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.counter_value, state.completed, state.total), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        is TileDownloadState.Complete -> StatusPill("Tiles ready", AccentCyan, modifier)
        is TileDownloadState.Failed -> StatusPill(state.message, AccentAmber, modifier)
    }
}

@Composable
private fun StatusPill(text: String, color: Color, modifier: Modifier) {
    Text(
        text = text,
        modifier = modifier
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.onBackground,
        style = MaterialTheme.typography.labelMedium,
    )
}

private fun ensureLayers(style: Style, textColor: Int) {
    if (style.getSource(OWN_SOURCE) == null) style.addSource(emptyGeoJsonSource(OWN_SOURCE))
    if (style.getSource(REMOTE_SOURCE) == null) style.addSource(emptyGeoJsonSource(REMOTE_SOURCE))
    if (style.getSource(WAYPOINT_SOURCE) == null) style.addSource(emptyGeoJsonSource(WAYPOINT_SOURCE))
    if (style.getSource(TILE_SELECTION_SOURCE) == null) style.addSource(emptyGeoJsonSource(TILE_SELECTION_SOURCE))
    if (style.getSource(MISSION_OVERLAY_EDIT_SOURCE) == null) style.addSource(emptyGeoJsonSource(MISSION_OVERLAY_EDIT_SOURCE))

    if (style.getLayer(TILE_SELECTION_FILL_LAYER) == null) {
        style.addLayer(
            FillLayer(TILE_SELECTION_FILL_LAYER, TILE_SELECTION_SOURCE).withProperties(
                fillColor("#00D4E0"),
                fillOpacity(0.14f),
            ),
        )
    }
    if (style.getLayer(TILE_SELECTION_LINE_LAYER) == null) {
        style.addLayer(
            LineLayer(TILE_SELECTION_LINE_LAYER, TILE_SELECTION_SOURCE).withProperties(
                lineColor("#00D4E0"),
                lineWidth(2.4f),
            ),
        )
    }
    if (style.getLayer(MISSION_OVERLAY_EDIT_LINE_LAYER) == null) {
        style.addLayer(
            LineLayer(MISSION_OVERLAY_EDIT_LINE_LAYER, MISSION_OVERLAY_EDIT_SOURCE).withProperties(
                lineColor("#00D4E0"),
                lineWidth(2.4f),
            ),
        )
    }
    if (style.getLayer(MISSION_OVERLAY_EDIT_HANDLE_LAYER) == null) {
        style.addLayer(
            CircleLayer(MISSION_OVERLAY_EDIT_HANDLE_LAYER, MISSION_OVERLAY_EDIT_SOURCE).withProperties(
                circleRadius(7f),
                circleColor("#00D4E0"),
                circleStrokeColor("#0A0F14"),
                circleStrokeWidth(2f),
            ),
        )
    }

    if (style.getLayer(OWN_LAYER) == null) {
        style.addLayer(
            CircleLayer(OWN_LAYER, OWN_SOURCE).withProperties(
                circleRadius(9f),
                circleColor("#00D4E0"),
                circleStrokeColor("#E6F1F7"),
                circleStrokeWidth(2f),
            ),
        )
    }
    if (style.getLayer(REMOTE_LAYER) == null) {
        style.addLayer(
            CircleLayer(REMOTE_LAYER, REMOTE_SOURCE).withProperties(
                circleRadius(8f),
                circleColor(Expression.toColor(Expression.get("pinColor"))),
                circleOpacity(Expression.get("pinOpacity")),
                circleStrokeColor(Expression.toColor(Expression.get("strokeColor"))),
                circleStrokeWidth(Expression.get("strokeWidth")),
            ),
        )
    }
    if (style.getLayer(REMOTE_LABEL_LAYER) == null) {
        style.addLayer(
            SymbolLayer(REMOTE_LABEL_LAYER, REMOTE_SOURCE).withProperties(
                textField(Expression.get("label")),
                textFont(arrayOf("Noto Sans Regular")),
                textSize(14f),
                textOffset(arrayOf(0f, -1.35f)),
                textColor("#%06X".format(textColor and UI_RGB_MASK)),
                textHaloColor("#000000"),
                textHaloWidth(1.2f),
                textAllowOverlap(true),
                textIgnorePlacement(true),
            ),
        )
    }
    if (style.getLayer(WAYPOINT_LAYER) == null) {
        style.addLayer(
            CircleLayer(WAYPOINT_LAYER, WAYPOINT_SOURCE).withProperties(
                circleRadius(6f),
                circleColor("#FFB300"),
                circleStrokeColor("#0A0F14"),
                circleStrokeWidth(2f),
            ),
        )
    }
    if (style.getLayer(WAYPOINT_LABEL_LAYER) == null) {
        style.addLayer(
            SymbolLayer(WAYPOINT_LABEL_LAYER, WAYPOINT_SOURCE).withProperties(
                textField(Expression.get("label")),
                textFont(arrayOf("Noto Sans Regular")),
                textSize(14f),
                textOffset(arrayOf(0f, 1.2f)),
                textColor("#%06X".format(textColor and UI_RGB_MASK)),
                textHaloColor("#000000"),
                textHaloWidth(1.2f),
                textAllowOverlap(true),
                textIgnorePlacement(true),
            ),
        )
    }
    ensureMarkerLayers(style, textColor)
}

private fun ensureMarkerLayers(style: Style, textColor: Int) {
    if (style.getSource(MARKER_AREA_SOURCE) == null) style.addSource(emptyGeoJsonSource(MARKER_AREA_SOURCE))
    if (style.getSource(MARKER_ROUTE_SOURCE) == null) style.addSource(emptyGeoJsonSource(MARKER_ROUTE_SOURCE))
    if (style.getSource(MARKER_POINT_SOURCE) == null) style.addSource(emptyGeoJsonSource(MARKER_POINT_SOURCE))
    if (style.getSource(MARKER_ROUTE_END_SOURCE) == null) style.addSource(emptyGeoJsonSource(MARKER_ROUTE_END_SOURCE))
    if (style.getSource(DRAFT_SOURCE) == null) style.addSource(emptyGeoJsonSource(DRAFT_SOURCE))

    if (style.getLayer(MARKER_AREA_FILL_LAYER) == null) {
        style.addLayer(
            FillLayer(MARKER_AREA_FILL_LAYER, MARKER_AREA_SOURCE).withProperties(
                fillColor(Expression.toColor(Expression.get("markerColor"))),
                fillOpacity(0.22f),
            ),
        )
    }
    if (style.getLayer(MARKER_AREA_LINE_LAYER) == null) {
        style.addLayer(
            LineLayer(MARKER_AREA_LINE_LAYER, MARKER_AREA_SOURCE).withProperties(
                lineColor(Expression.toColor(Expression.get("markerColor"))),
                lineWidth(2.4f),
            ),
        )
    }
    if (style.getLayer(MARKER_ROUTE_LINE_LAYER) == null) {
        style.addLayer(
            LineLayer(MARKER_ROUTE_LINE_LAYER, MARKER_ROUTE_SOURCE).withProperties(
                lineColor(Expression.toColor(Expression.get("markerColor"))),
                lineWidth(5f),
            ),
        )
    }
    if (style.getLayer(MARKER_POINT_LAYER) == null) {
        style.addLayer(
            CircleLayer(MARKER_POINT_LAYER, MARKER_POINT_SOURCE).withProperties(
                circleRadius(9f),
                circleColor(Expression.toColor(Expression.get("markerColor"))),
                circleStrokeColor("#0A0F14"),
                circleStrokeWidth(2f),
            ),
        )
    }
    val haloColor = if ((textColor and UI_RGB_MASK) < UI_HALO_LUMINANCE_THRESHOLD) "#FFFFFF" else "#000000"
    val labelColor = "#%06X".format(textColor and UI_RGB_MASK)
    if (style.getLayer(MARKER_POINT_LABEL_LAYER) == null) {
        style.addLayer(
            SymbolLayer(MARKER_POINT_LABEL_LAYER, MARKER_POINT_SOURCE).withProperties(
                textField(Expression.get("label")),
                textFont(arrayOf("Noto Sans Regular")),
                textSize(14f),
                textOffset(arrayOf(0f, 1.3f)),
                textColor(labelColor),
                textHaloColor(haloColor),
                textHaloWidth(1.2f),
                textAllowOverlap(true),
                textIgnorePlacement(true),
            ),
        )
    }
    if (style.getLayer(MARKER_ROUTE_LABEL_LAYER) == null) {
        style.addLayer(
            SymbolLayer(MARKER_ROUTE_LABEL_LAYER, MARKER_ROUTE_SOURCE).withProperties(
                textField(Expression.get("label")),
                textFont(arrayOf("Noto Sans Regular")),
                textSize(14f),
                textColor(labelColor),
                textHaloColor(haloColor),
                textHaloWidth(1.2f),
                symbolPlacement(Property.SYMBOL_PLACEMENT_LINE_CENTER),
                textMaxAngle(0f),
                textKeepUpright(true),
                textAllowOverlap(true),
                textIgnorePlacement(true),
                textOffset(arrayOf(0f, -1.2f)),
            ),
        )
    }
    if (style.getLayer(MARKER_ROUTE_ARROW_LAYER) == null) {
        style.addLayer(
            SymbolLayer(MARKER_ROUTE_ARROW_LAYER, MARKER_ROUTE_SOURCE).withProperties(
                textField("▶"),
                textFont(arrayOf("Noto Sans Regular")),
                textSize(16f),
                textColor(Expression.toColor(Expression.get("markerColor"))),
                textHaloColor(haloColor),
                textHaloWidth(1.6f),
                symbolPlacement(Property.SYMBOL_PLACEMENT_LINE),
                symbolSpacing(120f),
                textKeepUpright(false),
                textRotationAlignment(Property.TEXT_ROTATION_ALIGNMENT_MAP),
                textPitchAlignment(Property.TEXT_PITCH_ALIGNMENT_VIEWPORT),
                textAllowOverlap(true),
                textIgnorePlacement(true),
            ),
        )
    }
    if (style.getLayer(MARKER_AREA_LABEL_LAYER) == null) {
        style.addLayer(
            SymbolLayer(MARKER_AREA_LABEL_LAYER, MARKER_AREA_SOURCE).withProperties(
                textField(Expression.get("label")),
                textFont(arrayOf("Noto Sans Regular")),
                textSize(14f),
                textColor(labelColor),
                textHaloColor(haloColor),
                textHaloWidth(1.2f),
                textAllowOverlap(true),
                textIgnorePlacement(true),
            ),
        )
    }
    if (style.getLayer(MARKER_ROUTE_END_ARROW_LAYER) == null) {
        style.addLayer(
            SymbolLayer(MARKER_ROUTE_END_ARROW_LAYER, MARKER_ROUTE_END_SOURCE).withProperties(
                textField("▼"),
                textFont(arrayOf("Noto Sans Regular")),
                textSize(18f),
                textColor(Expression.toColor(Expression.get("markerColor"))),
                textHaloColor(haloColor),
                textHaloWidth(1.6f),
                textAllowOverlap(true),
                textIgnorePlacement(true),
            ),
        )
    }
    // Draft preview while the user is placing points.
    if (style.getLayer(DRAFT_FILL_LAYER) == null) {
        style.addLayer(
            FillLayer(DRAFT_FILL_LAYER, DRAFT_SOURCE).withProperties(
                fillColor("#FFB300"),
                fillOpacity(0.18f),
            ),
        )
    }
    if (style.getSource(NAV_ROUTE_SOURCE) == null) style.addSource(emptyGeoJsonSource(NAV_ROUTE_SOURCE))
    if (style.getLayer(NAV_ROUTE_LINE_LAYER) == null) {
        style.addLayer(
            LineLayer(NAV_ROUTE_LINE_LAYER, NAV_ROUTE_SOURCE).withProperties(
                lineColor("#FF4D5E"),
                lineWidth(5f),
            ),
        )
    }
    if (style.getLayer(DRAFT_LINE_LAYER) == null) {
        style.addLayer(
            LineLayer(DRAFT_LINE_LAYER, DRAFT_SOURCE).withProperties(
                lineColor("#FFB300"),
                lineWidth(2.4f),
            ),
        )
    }
    if (style.getLayer(DRAFT_POINT_LAYER) == null) {
        style.addLayer(
            CircleLayer(DRAFT_POINT_LAYER, DRAFT_SOURCE).withProperties(
                circleRadius(5f),
                circleColor("#FFB300"),
                circleStrokeColor("#0A0F14"),
                circleStrokeWidth(2f),
            ),
        )
    }
}

private fun updateLayers(style: Style, ui: MapUiState) {
    style.getSourceAs<GeoJsonSource>(OWN_SOURCE)?.setGeoJson(
        ui.ownPosition?.let { pos ->
            FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(pos.longitude, pos.latitude)))
        } ?: emptyFeatureCollection(),
    )
    style.getSourceAs<GeoJsonSource>(REMOTE_SOURCE)?.setGeoJson(
        if (ui.showPlayers) FeatureCollection.fromFeatures(ui.nodes.map { it.toFeature() }) else emptyFeatureCollection(),
    )
    style.getSourceAs<GeoJsonSource>(WAYPOINT_SOURCE)?.setGeoJson(
        if (ui.showWaypoints) FeatureCollection.fromFeatures(ui.waypoints.map { it.toFeature() }) else emptyFeatureCollection(),
    )
}

private fun updateMarkerLayers(style: Style, markers: List<MarkerVisual>) {
    val pointFeatures = mutableListOf<Feature>()
    val routeFeatures = mutableListOf<Feature>()
    val routeEndFeatures = mutableListOf<Feature>()
    val areaFeatures = mutableListOf<Feature>()
    for (m in markers) {
        val colorHex = "#%06X".format(m.colorRgb and UI_RGB_MASK)
        when (m.type) {
            MarkerType.MARKER_TYPE_POINT,
            MarkerType.MARKER_TYPE_NOTE,
            MarkerType.MARKER_TYPE_OBJECTIVE -> {
                val p = m.points.firstOrNull() ?: continue
                pointFeatures += Feature.fromGeometry(Point.fromLngLat(p.second, p.first)).apply {
                    addStringProperty("label", m.label)
                    addStringProperty("markerColor", colorHex)
                    addStringProperty("markerId", m.markerId.toString())
                }
            }
            MarkerType.MARKER_TYPE_ROUTE -> {
                if (m.points.size < 2) continue
                val lngLats = m.points.map { Point.fromLngLat(it.second, it.first) }
                routeFeatures += Feature.fromGeometry(
                    LineString.fromLngLats(lngLats),
                ).apply {
                    addStringProperty("label", m.label)
                    addStringProperty("markerColor", colorHex)
                    addStringProperty("markerId", m.markerId.toString())
                }
                routeEndFeatures += Feature.fromGeometry(lngLats.last()).apply {
                    addStringProperty("markerColor", colorHex)
                }
            }
            MarkerType.MARKER_TYPE_AREA -> {
                if (m.points.size < 3) continue
                val ring = (m.points + m.points.first()).map { Point.fromLngLat(it.second, it.first) }
                areaFeatures += Feature.fromGeometry(Polygon.fromLngLats(listOf(ring))).apply {
                    addStringProperty("label", m.label)
                    addStringProperty("markerColor", colorHex)
                    addStringProperty("markerId", m.markerId.toString())
                }
            }
            else -> {}
        }
    }
    style.getSourceAs<GeoJsonSource>(MARKER_POINT_SOURCE)
        ?.setGeoJson(FeatureCollection.fromFeatures(pointFeatures))
    style.getSourceAs<GeoJsonSource>(MARKER_ROUTE_SOURCE)
        ?.setGeoJson(FeatureCollection.fromFeatures(routeFeatures))
    style.getSourceAs<GeoJsonSource>(MARKER_ROUTE_END_SOURCE)
        ?.setGeoJson(FeatureCollection.fromFeatures(routeEndFeatures))
    style.getSourceAs<GeoJsonSource>(MARKER_AREA_SOURCE)
        ?.setGeoJson(FeatureCollection.fromFeatures(areaFeatures))
}

private fun updateDraftLayer(style: Style, drawing: DrawingState) {
    val source = style.getSourceAs<GeoJsonSource>(DRAFT_SOURCE) ?: return
    if (drawing !is DrawingState.Active || drawing.points.isEmpty()) {
        source.setGeoJson(emptyFeatureCollection())
        return
    }
    val pts = drawing.points.map { Point.fromLngLat(it.second, it.first) }
    val features = mutableListOf<Feature>()
    when (drawing.tool) {
        MarkerTool.POINT, MarkerTool.NOTE, MarkerTool.OBJECTIVE -> {
            pts.forEach { features += Feature.fromGeometry(it) }
        }
        MarkerTool.ROUTE -> {
            pts.forEach { features += Feature.fromGeometry(it) }
            if (pts.size >= 2) features += Feature.fromGeometry(LineString.fromLngLats(pts))
        }
        MarkerTool.AREA -> {
            pts.forEach { features += Feature.fromGeometry(it) }
            if (pts.size >= 2) features += Feature.fromGeometry(LineString.fromLngLats(pts))
            if (pts.size >= 3) {
                val ring = pts + pts.first()
                features += Feature.fromGeometry(Polygon.fromLngLats(listOf(ring)))
            }
        }
    }
    source.setGeoJson(FeatureCollection.fromFeatures(features))
}

private fun updateNavRouteLayer(style: Style, state: RouteCalcState) {
    val source = style.getSourceAs<GeoJsonSource>(NAV_ROUTE_SOURCE) ?: return
    if (state !is RouteCalcState.Complete || state.points.size < 2) {
        source.setGeoJson(emptyFeatureCollection())
        return
    }
    val coordinates = state.points.map { Point.fromLngLat(it.longitude, it.latitude) }
    val line = LineString.fromLngLats(coordinates)
    source.setGeoJson(FeatureCollection.fromFeature(Feature.fromGeometry(line)))
}

private fun updateMissionOverlay(style: Style, overlay: MissionOverlay?) {
    if (overlay == null) {
        if (style.getLayer(MISSION_OVERLAY_LAYER) != null) style.removeLayer(MISSION_OVERLAY_LAYER)
        if (style.getSource(MISSION_OVERLAY_SOURCE) != null) style.removeSource(MISSION_OVERLAY_SOURCE)
        return
    }

    val bitmap = BitmapFactory.decodeFile(overlay.imagePath) ?: return
    val quad = overlay.corners.toLatLngQuad()
    val existing = style.getSourceAs<ImageSource>(MISSION_OVERLAY_SOURCE)
    if (existing == null) {
        style.addSource(ImageSource(MISSION_OVERLAY_SOURCE, quad, bitmap))
    } else {
        existing.setCoordinates(quad)
        existing.setImage(bitmap)
    }
    if (style.getLayer(MISSION_OVERLAY_LAYER) == null) {
        style.addLayerBelow(
            RasterLayer(MISSION_OVERLAY_LAYER, MISSION_OVERLAY_SOURCE).withProperties(
                rasterOpacity(overlay.opacity),
            ),
            TILE_SELECTION_FILL_LAYER,
        )
    } else {
        (style.getLayer(MISSION_OVERLAY_LAYER) as? RasterLayer)?.setProperties(rasterOpacity(overlay.opacity))
    }
}

private fun updateMissionOverlayEditLayer(style: Style, overlay: MissionOverlay?, editing: Boolean) {
    val source = style.getSourceAs<GeoJsonSource>(MISSION_OVERLAY_EDIT_SOURCE) ?: return
    source.setGeoJson(
        if (editing && overlay != null) overlay.corners.toEditFeatureCollection() else emptyFeatureCollection(),
    )
}

private fun MissionOverlayCorners.toLatLngQuad(): LatLngQuad = LatLngQuad(
    LatLng(topLeft.latitude, topLeft.longitude),
    LatLng(topRight.latitude, topRight.longitude),
    LatLng(bottomRight.latitude, bottomRight.longitude),
    LatLng(bottomLeft.latitude, bottomLeft.longitude),
)

private fun MissionOverlayCorners.toScreenPoints(map: MapLibreMap): Map<MissionOverlayCornerId, Offset> = mapOf(
    MissionOverlayCornerId.TOP_LEFT to topLeft.toScreenOffset(map),
    MissionOverlayCornerId.TOP_RIGHT to topRight.toScreenOffset(map),
    MissionOverlayCornerId.BOTTOM_RIGHT to bottomRight.toScreenOffset(map),
    MissionOverlayCornerId.BOTTOM_LEFT to bottomLeft.toScreenOffset(map),
)

private fun MissionOverlayPoint.toScreenOffset(map: MapLibreMap): Offset {
    val screen = map.projection.toScreenLocation(LatLng(latitude, longitude))
    return Offset(screen.x, screen.y)
}

private fun MissionOverlayCorners.toEditFeatureCollection(): FeatureCollection {
    val points = listOf(topLeft, topRight, bottomRight, bottomLeft)
    val line = Feature.fromGeometry(
        LineString.fromLngLats((points + topLeft).map { Point.fromLngLat(it.longitude, it.latitude) }),
    )
    val handles = points.map { point ->
        Feature.fromGeometry(Point.fromLngLat(point.longitude, point.latitude))
    }
    return FeatureCollection.fromFeatures(listOf(line) + handles)
}

private fun initialMissionOverlayCorners(map: MapLibreMap, mapView: MapView): MissionOverlayCorners {
    val width = mapView.width.takeIf { it > 0 } ?: 1000
    val height = mapView.height.takeIf { it > 0 } ?: 1000
    val left = width * 0.25f
    val right = width * 0.75f
    val top = height * 0.25f
    val bottom = height * 0.75f
    return MissionOverlayCorners(
        topLeft = map.projection.fromScreenLocation(PointF(left, top)).toMissionPoint(),
        topRight = map.projection.fromScreenLocation(PointF(right, top)).toMissionPoint(),
        bottomRight = map.projection.fromScreenLocation(PointF(right, bottom)).toMissionPoint(),
        bottomLeft = map.projection.fromScreenLocation(PointF(left, bottom)).toMissionPoint(),
    )
}

private fun LatLng.toMissionPoint(): MissionOverlayPoint = MissionOverlayPoint(latitude, longitude)

private fun northUpCamera(target: LatLng, zoom: Double) = CameraUpdateFactory.newCameraPosition(
    CameraPosition.Builder()
        .target(target)
        .zoom(zoom)
        .bearing(0.0)
        .tilt(0.0)
        .build(),
)

private fun MapNode.toFeature(): Feature {
    val stale = positionStale || playerOffline
    val status = PlayerStatus.forNumber(statusValue)
    val fillOpacity = when {
        stale -> 0.26
        status == PlayerStatus.PLAYER_STATUS_HIT -> 0.10
        status == PlayerStatus.PLAYER_STATUS_ELIMINATED -> 0.20
        status == PlayerStatus.PLAYER_STATUS_RESPAWNING -> 0.60
        else -> 0.92
    }
    val strokeWidth = when (status) {
        PlayerStatus.PLAYER_STATUS_HIT -> 3.5
        PlayerStatus.PLAYER_STATUS_ELIMINATED -> 2.8
        PlayerStatus.PLAYER_STATUS_RESPAWNING -> 2.2
        else -> 1.6
    }
    val strokeColor = when (status) {
        PlayerStatus.PLAYER_STATUS_HIT -> "#FF4D5E"
        PlayerStatus.PLAYER_STATUS_ELIMINATED -> "#80CBC4"
        PlayerStatus.PLAYER_STATUS_RESPAWNING -> "#FFB300"
        else -> "#E6F1F7"
    }
    val pinColor = when {
        stale -> "#6E7681"
        status == PlayerStatus.PLAYER_STATUS_ELIMINATED -> "#B0BEC5"
        else -> "#%06X".format(colorRgb and UI_RGB_MASK)
    }
    return Feature.fromGeometry(Point.fromLngLat(longitude, latitude)).apply {
        addStringProperty("label", label)
        addStringProperty("pinColor", pinColor)
        addStringProperty("strokeColor", strokeColor)
        addNumberProperty("pinOpacity", fillOpacity)
        addNumberProperty("strokeWidth", strokeWidth)
    }
}

private fun MapWaypoint.toFeature(): Feature = Feature.fromGeometry(Point.fromLngLat(longitude, latitude)).apply {
    addStringProperty("label", label)
}

private fun updateTileSelectionLayer(style: Style, selectedBounds: TileBounds?) {
    val feature = selectedBounds?.toFeature()
    style.getSourceAs<GeoJsonSource>(TILE_SELECTION_SOURCE)?.setGeoJson(
        feature?.let { FeatureCollection.fromFeature(it) } ?: emptyFeatureCollection(),
    )
}

private fun boundsFromCorners(a: LatLng, b: LatLng): TileBounds {
    return TileBounds(
        minLat = minOf(a.latitude, b.latitude),
        minLon = minOf(a.longitude, b.longitude),
        maxLat = maxOf(a.latitude, b.latitude),
        maxLon = maxOf(a.longitude, b.longitude),
    )
}

private fun TileBounds.toFeature(): Feature {
    val ring = listOf(
        Point.fromLngLat(minLon, minLat),
        Point.fromLngLat(maxLon, minLat),
        Point.fromLngLat(maxLon, maxLat),
        Point.fromLngLat(minLon, maxLat),
        Point.fromLngLat(minLon, minLat),
    )
    return Feature.fromGeometry(Polygon.fromLngLats(listOf(ring)))
}

private fun TileBounds.displayString(): String =
    "${minLat.formatCoord()}, ${minLon.formatCoord()} / ${maxLat.formatCoord()}, ${maxLon.formatCoord()}"

private fun Double.formatCoord(): String = "%.5f".format(this)

private fun estimateTileCount(bounds: TileBounds, minZoom: Int, maxZoom: Int): Int {
    val normalized = bounds.normalizedForEstimate()
    var total = 0L
    for (zoom in minZoom..maxZoom) {
        val topLeft = latLonToTile(normalized.maxLat, normalized.minLon, zoom)
        val bottomRight = latLonToTile(normalized.minLat, normalized.maxLon, zoom)
        total += (bottomRight.x - topLeft.x + 1).toLong() * (bottomRight.y - topLeft.y + 1).toLong()
        if (total > Int.MAX_VALUE) return Int.MAX_VALUE
    }
    return total.toInt()
}

private fun TileBounds.normalizedForEstimate(): TileBounds = TileBounds(
    minLat = minOf(minLat, maxLat).coerceIn(-85.05112878, 85.05112878),
    minLon = minOf(minLon, maxLon).coerceIn(-180.0, 180.0),
    maxLat = maxOf(minLat, maxLat).coerceIn(-85.05112878, 85.05112878),
    maxLon = maxOf(minLon, maxLon).coerceIn(-180.0, 180.0),
)

private fun latLonToTile(lat: Double, lon: Double, zoom: Int): TileXY {
    val clampedLat = lat.coerceIn(-85.05112878, 85.05112878)
    val clampedLon = lon.coerceIn(-180.0, 180.0)
    val n = 2.0.pow(zoom)
    val x = ((clampedLon + 180.0) / 360.0 * n).toInt().coerceIn(0, n.toInt() - 1)
    val latRad = Math.toRadians(clampedLat)
    val y = ((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n).toInt()
        .coerceIn(0, n.toInt() - 1)
    return TileXY(x, y)
}

private data class TileXY(val x: Int, val y: Int)

private fun emptyGeoJsonSource(id: String): GeoJsonSource = GeoJsonSource(id, emptyFeatureCollection())
private fun emptyFeatureCollection(): FeatureCollection = FeatureCollection.fromFeatures(emptyList())

private fun mapStyleJson(
    @androidx.annotation.ColorInt bgArgb: Int,
    baseTileTemplate: String?,
    localTileTemplate: String?,
    localTileMaxZoom: Int?,
    isDark: Boolean,
): String {
    val bgHex = "#%06X".format(bgArgb and UI_RGB_MASK)
    val baseSource = baseTileTemplate?.jsonEscaped()?.let { url ->
        ",\n        \"base-tiles\": { \"type\": \"raster\", \"tiles\": [\"$url\"], \"tileSize\": $MAP_TILE_SIZE_PX }"
    }.orEmpty()
    val localSource = localTileTemplate?.jsonEscaped()?.let { url ->
        val maxZoom = localTileMaxZoom?.let { ", \"maxzoom\": $it" }.orEmpty()
        ",\n        \"local-tiles\": { \"type\": \"raster\", \"tiles\": [\"$url\"], \"tileSize\": $MAP_TILE_SIZE_PX$maxZoom }"
    }.orEmpty()
    val baseLayer = if (baseTileTemplate != null) {
        ",\n        { \"id\": \"base-tiles\", \"type\": \"raster\", \"source\": \"base-tiles\" }"
    } else ""
    val localLayer = if (localTileTemplate != null) {
        ",\n        { \"id\": \"local-tiles\", \"type\": \"raster\", \"source\": \"local-tiles\" }"
    } else ""
    return """
    {
      "version": 8,
      "name": "AirTAK Field",
      "glyphs": "asset://glyphs/{fontstack}/{range}.pbf",
      "sources": {
        "blank": { "type": "geojson", "data": { "type": "FeatureCollection", "features": [] } }
        $baseSource
        $localSource
      },
      "layers": [
        { "id": "background", "type": "background", "paint": { "background-color": "$bgHex" } }
        $baseLayer
        $localLayer
      ]
    }
    """.trimIndent()
}

private fun String.jsonEscaped(): String = replace("\\", "\\\\").replace("\"", "\\\"")

private const val OWN_SOURCE = "own-position-source"
private const val OWN_LAYER = "own-position-layer"
private const val MISSION_OVERLAY_SOURCE = "mission-overlay-source"
private const val MISSION_OVERLAY_LAYER = "mission-overlay-layer"
private const val MISSION_OVERLAY_EDIT_SOURCE = "mission-overlay-edit-source"
private const val MISSION_OVERLAY_EDIT_LINE_LAYER = "mission-overlay-edit-line-layer"
private const val MISSION_OVERLAY_EDIT_HANDLE_LAYER = "mission-overlay-edit-handle-layer"
private const val REMOTE_SOURCE = "remote-node-source"
private const val REMOTE_LAYER = "remote-node-layer"
private const val REMOTE_LABEL_LAYER = "remote-node-label-layer"
private const val WAYPOINT_SOURCE = "waypoint-source"
private const val WAYPOINT_LAYER = "waypoint-layer"
private const val WAYPOINT_LABEL_LAYER = "waypoint-label-layer"
private const val TILE_SELECTION_SOURCE = "tile-selection-source"
private const val TILE_SELECTION_FILL_LAYER = "tile-selection-fill-layer"
private const val TILE_SELECTION_LINE_LAYER = "tile-selection-line-layer"
private const val MARKER_AREA_SOURCE = "marker-area-source"
private const val MARKER_AREA_FILL_LAYER = "marker-area-fill-layer"
private const val MARKER_AREA_LINE_LAYER = "marker-area-line-layer"
private const val MARKER_ROUTE_SOURCE = "marker-route-source"
private const val MARKER_ROUTE_LINE_LAYER = "marker-route-line-layer"
private const val MARKER_ROUTE_LABEL_LAYER = "marker-route-label-layer"
private const val MARKER_ROUTE_ARROW_LAYER = "marker-route-arrow-layer"
private const val MARKER_ROUTE_END_SOURCE = "marker-route-end-source"
private const val MARKER_ROUTE_END_ARROW_LAYER = "marker-route-end-arrow-layer"
private const val MARKER_POINT_SOURCE = "marker-point-source"
private const val MARKER_POINT_LAYER = "marker-point-layer"
private const val MARKER_POINT_LABEL_LAYER = "marker-point-label-layer"
private const val MARKER_AREA_LABEL_LAYER = "marker-area-label-layer"
private const val DRAFT_SOURCE = "draft-marker-source"
private const val DRAFT_POINT_LAYER = "draft-marker-point-layer"
private const val DRAFT_LINE_LAYER = "draft-marker-line-layer"
private const val DRAFT_FILL_LAYER = "draft-marker-fill-layer"
private const val NAV_ROUTE_SOURCE = "nav-route-source"
private const val NAV_ROUTE_LINE_LAYER = "nav-route-line-layer"


private fun statusShortLabel(status: PlayerStatus): String = when (status) {
    PlayerStatus.PLAYER_STATUS_ACTIVE -> "ACTIVE"
    PlayerStatus.PLAYER_STATUS_INACTIVE -> "INACT"
    PlayerStatus.PLAYER_STATUS_HIT -> "HIT"
    PlayerStatus.PLAYER_STATUS_RESPAWNING -> "RESPAWN"
    PlayerStatus.PLAYER_STATUS_ELIMINATED -> "ELIM"
    else -> "UNKNOWN"
}

// TODO: centralize to Color.kt
private fun statusColor(status: PlayerStatus): Color = when (status) {
    PlayerStatus.PLAYER_STATUS_ACTIVE -> Color(0xFF00E676)
    PlayerStatus.PLAYER_STATUS_INACTIVE -> Color(0xFF6E7681)
    PlayerStatus.PLAYER_STATUS_HIT -> Color(0xFFFF4D5E)
    PlayerStatus.PLAYER_STATUS_RESPAWNING -> Color(0xFFFFB300)
    PlayerStatus.PLAYER_STATUS_ELIMINATED -> Color(0xFF80CBC4)
    else -> Color(0xFF6E7681)
}

private fun statusDescription(status: PlayerStatus): String = when (status) {
    PlayerStatus.PLAYER_STATUS_ACTIVE -> "In play, full position broadcasting"
    PlayerStatus.PLAYER_STATUS_INACTIVE -> "Paused, reduced broadcasting"
    PlayerStatus.PLAYER_STATUS_HIT -> "Hit — waiting to respawn"
    PlayerStatus.PLAYER_STATUS_RESPAWNING -> "Returning to play"
    PlayerStatus.PLAYER_STATUS_ELIMINATED -> "Out for the remainder"
    else -> ""
}
private const val MISSION_OVERLAY_IMAGE_MIME = "image/*"