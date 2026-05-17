// AirTAK Android — ui/map/MapScreen.kt

package com.airtak.ui.map

import android.graphics.BitmapFactory
import android.graphics.PointF
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.airtak.data.tiles.TileBounds
import com.airtak.data.tiles.TileDownloadState
import com.airtak.data.overlays.MissionOverlay
import com.airtak.data.geocoding.GeocodingResult
import com.airtak.data.overlays.MissionOverlayCornerId
import com.airtak.data.overlays.MissionOverlayCorners
import com.airtak.data.overlays.MissionOverlayPoint
import com.airtak.proto.PlayerStatus
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
import org.maplibre.android.style.layers.PropertyFactory.rasterOpacity
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.textOffset
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
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tan

@Composable
fun MapScreen(
    viewModel: MapViewModel = hiltViewModel(),
    focusNodeId: Long? = null,
    onFocusConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val ui by viewModel.uiState.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
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

    DisposableEffect(map, tileSelectMode, tileFirstCorner, missionOverlayEditMode, selectedOverlayCorner, ui.missionOverlay) {
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
                } else {
                    false
                }
            }
            val longClickListener = MapLibreMap.OnMapLongClickListener { latLng ->
                if (missionOverlayEditMode && ui.missionOverlay != null) {
                    // Corner alignment is handled by draggable screen-space handles.
                } else if (tileSelectMode) {
                    markTileCorner(latLng)
                } else {
                    pendingWaypoint = latLng
                    waypointLabel = ""
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

    LaunchedEffect(map, tileFirstCorner, selectedTileBounds) {
        map?.getStyle { style -> updateTileSelectionLayer(style, selectedTileBounds) }
    }

    LaunchedEffect(ui.ownPosition) {
        val pos = ui.ownPosition
        if (!centeredOnce && pos != null) {
            centeredOnce = true
            map?.animateCamera(northUpCamera(LatLng(pos.latitude, pos.longitude), 14.0))
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
    LaunchedEffect(map, focusTarget) {
        val target = focusTarget ?: return@LaunchedEffect
        if (map != null) {
            map?.animateCamera(northUpCamera(LatLng(target.latitude, target.longitude), 16.0))
            viewModel.clearFocus()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        MapToolbar(
            showPlayers = ui.showPlayers,
            showWaypoints = ui.showWaypoints,
            selectingTiles = tileSelectMode,
            onTogglePlayers = { viewModel.setShowPlayers(!ui.showPlayers) },
            onToggleWaypoints = { viewModel.setShowWaypoints(!ui.showWaypoints) },
            onSearch = { showSearch = true },
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
                tileFirstCorner = null
                selectedTileBounds = null
                tileUrl = ui.configuredTileTemplate
            },
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
        )

        val missionOverlay = ui.missionOverlay
        if (missionOverlayEditMode && missionOverlay != null) {
            MissionOverlayControls(
                selectedCorner = selectedOverlayCorner,
                opacity = missionOverlay.opacity,
                onOpacityChange = viewModel::setMissionOverlayOpacity,
                onNudge = { dx, dy -> nudgeOverlayCorner(selectedOverlayCorner, dx, dy) },
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

        FloatingActionButton(
            onClick = {
                val pos = ui.ownPosition ?: return@FloatingActionButton
                map?.animateCamera(northUpCamera(LatLng(pos.latitude, pos.longitude), 14.0))
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .size(56.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Icon(Icons.Outlined.MyLocation, contentDescription = "Recenter")
        }
    }

    pendingWaypoint?.let { latLng ->
        AlertDialog(
            onDismissRequest = { pendingWaypoint = null },
            title = { Text("Waypoint") },
            text = {
                OutlinedTextField(
                    value = waypointLabel,
                    onValueChange = { waypointLabel = it.take(24) },
                    label = { Text("Label") },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.addWaypoint(latLng.latitude, latLng.longitude, waypointLabel)
                    pendingWaypoint = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { pendingWaypoint = null }) { Text("Cancel") }
            },
        )
    }

    selectedTileBounds?.let { bounds ->
        val minZoom = zoomRange.start.toInt()
        val maxZoom = zoomRange.endInclusive.toInt()
        val tileCount = remember(bounds, minZoom, maxZoom) { estimateTileCount(bounds, minZoom, maxZoom) }
        val tileUrlValid = tileUrl.contains("{z}") && tileUrl.contains("{x}") && tileUrl.contains("{y}")
        val tooManyTiles = tileCount > MAX_TILES_PER_DOWNLOAD
        AlertDialog(
            onDismissRequest = { selectedTileBounds = null },
            title = { Text("Tile Region") },
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
                        onValueChange = { tileUrl = it.take(512) },
                        label = { Text("Tile URL") },
                        placeholder = { Text("https://server/{z}/{x}/{y}.png") },
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
                ) { Text("Download") }
            },
            dismissButton = {
                TextButton(onClick = { selectedTileBounds = null }) { Text("Cancel") }
            },
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
        title = { Text("Search Location") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        label = { Text("Town, place, coordinates...") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = onSearch, enabled = query.isNotBlank() && !isSearching) {
                        Text("Go")
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
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun MapToolbar(
    showPlayers: Boolean,
    showWaypoints: Boolean,
    selectingTiles: Boolean,
    onTogglePlayers: () -> Unit,
    onToggleWaypoints: () -> Unit,
    onSearch: () -> Unit,
    hasMissionOverlay: Boolean,
    editingMissionOverlay: Boolean,
    onMissionOverlay: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            IconButton(
                onClick = onSearch,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) { Icon(Icons.Outlined.Search, contentDescription = "Search location") }
            IconButton(
                onClick = onTogglePlayers,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = if (showPlayers) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) { Icon(Icons.Outlined.Groups, contentDescription = "Players") }
            IconButton(
                onClick = onToggleWaypoints,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = if (showWaypoints) AccentAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) { Icon(Icons.Outlined.Place, contentDescription = "Waypoints") }
            IconButton(
                onClick = onMissionOverlay,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = if (editingMissionOverlay || hasMissionOverlay) AccentCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) { Icon(Icons.Outlined.Image, contentDescription = "Mission overlay") }
            IconButton(
                onClick = onDownload,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = if (selectingTiles) AccentCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Icon(Icons.Outlined.Download, contentDescription = "Download tiles")
            }
        }
    }
}

@Composable
private fun MissionOverlayControls(
    selectedCorner: MissionOverlayCornerId,
    opacity: Float,
    onOpacityChange: (Float) -> Unit,
    onNudge: (Float, Float) -> Unit,
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
            Text("Mission Overlay", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.width(220.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Opacity", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = opacity.coerceIn(0.1f, 1f),
                    onValueChange = onOpacityChange,
                    valueRange = 0.1f..1f,
                    modifier = Modifier.weight(1f),
                )
                Text("${(opacity * 100f).roundToInt()}%", style = MaterialTheme.typography.labelSmall)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Nudge ${selectedCorner.shortLabel()}", style = MaterialTheme.typography.labelSmall)
                IconButton(onClick = { onNudge(0f, -NUDGE_PX) }) {
                    Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "Nudge up")
                }
                IconButton(onClick = { onNudge(-NUDGE_PX, 0f) }) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = "Nudge left")
                }
                IconButton(onClick = { onNudge(NUDGE_PX, 0f) }) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "Nudge right")
                }
                IconButton(onClick = { onNudge(0f, NUDGE_PX) }) {
                    Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Nudge down")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onReplace) { Text("Replace") }
                TextButton(onClick = onRemove) { Text("Remove") }
                Button(onClick = onDone) { Text("Done") }
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

private fun MissionOverlayCornerId.shortLabel(): String = when (this) {
    MissionOverlayCornerId.TOP_LEFT -> "TL"
    MissionOverlayCornerId.TOP_RIGHT -> "TR"
    MissionOverlayCornerId.BOTTOM_RIGHT -> "BR"
    MissionOverlayCornerId.BOTTOM_LEFT -> "BL"
}

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
                    Text("${state.completed}/${state.total}", style = MaterialTheme.typography.labelMedium)
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
                textSize(12f),
                textOffset(arrayOf(0f, -1.35f)),
                textColor("#%06X".format(textColor and 0xFFFFFF)),
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
                textSize(12f),
                textOffset(arrayOf(0f, 1.2f)),
                textColor("#%06X".format(textColor and 0xFFFFFF)),
                textHaloColor("#000000"),
                textHaloWidth(1.2f),
                textAllowOverlap(true),
                textIgnorePlacement(true),
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
        else -> 0.92
    }
    val strokeWidth = when (status) {
        PlayerStatus.PLAYER_STATUS_HIT -> 3.5
        PlayerStatus.PLAYER_STATUS_ELIMINATED -> 2.8
        else -> 1.6
    }
    return Feature.fromGeometry(Point.fromLngLat(longitude, latitude)).apply {
        addStringProperty("label", label)
        addStringProperty("pinColor", if (stale) "#6E7681" else "#%06X".format(colorRgb and 0xFFFFFF))
        addStringProperty("strokeColor", if (status == PlayerStatus.PLAYER_STATUS_HIT) "#FF4D5E" else "#E6F1F7")
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
    val bgHex = "#%06X".format(bgArgb and 0xFFFFFF)
    val baseSource = baseTileTemplate?.jsonEscaped()?.let { url ->
        ",\n        \"base-tiles\": { \"type\": \"raster\", \"tiles\": [\"$url\"], \"tileSize\": 256 }"
    }.orEmpty()
    val localSource = localTileTemplate?.jsonEscaped()?.let { url ->
        val maxZoom = localTileMaxZoom?.let { ", \"maxzoom\": $it" }.orEmpty()
        ",\n        \"local-tiles\": { \"type\": \"raster\", \"tiles\": [\"$url\"], \"tileSize\": 256$maxZoom }"
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
private const val MAX_TILES_PER_DOWNLOAD = 15_000
private const val NUDGE_PX = 2f
private const val MISSION_OVERLAY_IMAGE_MIME = "image/*"