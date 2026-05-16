// AirTAK Android — ui/map/MapScreen.kt

package com.airtak.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Place
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.airtak.data.tiles.TileBounds
import com.airtak.data.tiles.TileDownloadState
import com.airtak.proto.PlayerStatus
import com.airtak.ui.theme.AccentAmber
import com.airtak.ui.theme.AccentCyan
import com.airtak.ui.theme.DarkBackground
import com.airtak.ui.theme.LightBackground
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
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
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

@Composable
fun MapScreen(
    viewModel: MapViewModel = hiltViewModel(),
    focusNodeId: Long? = null,
    onFocusConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val ui by viewModel.uiState.collectAsState()
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
    var zoomRange by remember { mutableStateOf(6f..17f) }

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
                readyMap.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(0.0, 0.0))
                    .zoom(2.0)
                    .build()
            }
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

    DisposableEffect(map, tileSelectMode, tileFirstCorner) {
        val targetMap = map
        if (targetMap == null) {
            onDispose {}
        } else {
            val clickListener = MapLibreMap.OnMapClickListener { latLng ->
                if (tileSelectMode) {
                    markTileCorner(latLng)
                    true
                } else {
                    false
                }
            }
            val longClickListener = MapLibreMap.OnMapLongClickListener { latLng ->
                if (tileSelectMode) {
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

    LaunchedEffect(map, bgColor, ui.tileTemplate, textColor, isDark) {
        map?.setStyle(Style.Builder().fromJson(mapStyleJson(bgColor, ui.tileTemplate, isDark))) { style ->
            ensureLayers(style, textColor)
            updateLayers(style, ui)
            updateTileSelectionLayer(style, selectedTileBounds)
        }
    }

    LaunchedEffect(map, ui) {
        map?.getStyle { style -> updateLayers(style, ui) }
    }

    LaunchedEffect(map, tileFirstCorner, selectedTileBounds) {
        map?.getStyle { style -> updateTileSelectionLayer(style, selectedTileBounds) }
    }

    LaunchedEffect(ui.ownPosition) {
        val pos = ui.ownPosition
        if (!centeredOnce && pos != null) {
            centeredOnce = true
            map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(pos.latitude, pos.longitude), 14.0))
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
            map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(target.latitude, target.longitude), 16.0))
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
            onDownload = {
                tileSelectMode = !tileSelectMode
                tileFirstCorner = null
                selectedTileBounds = null
            },
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
        )

        TileStatus(
            state = ui.tileDownload,
            tileTemplate = ui.tileTemplate,
            selectingTiles = tileSelectMode,
            firstCorner = tileFirstCorner != null,
            modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
        )

        FloatingActionButton(
            onClick = {
                val pos = ui.ownPosition ?: return@FloatingActionButton
                map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(pos.latitude, pos.longitude), 14.0))
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
                        "Zoom ${zoomRange.start.toInt()}-${zoomRange.endInclusive.toInt()}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    RangeSlider(
                        value = zoomRange,
                        onValueChange = { range ->
                            val start = range.start.toInt().coerceIn(6, 17).toFloat()
                            val end = range.endInclusive.toInt().coerceIn(start.toInt(), 17).toFloat()
                            zoomRange = start..end
                        },
                        valueRange = 6f..17f,
                        steps = 10,
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.downloadVisible(bounds, zoomRange.start.toInt(), zoomRange.endInclusive.toInt())
                    selectedTileBounds = null
                }) { Text("Download") }
            },
            dismissButton = {
                TextButton(onClick = { selectedTileBounds = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun MapToolbar(
    showPlayers: Boolean,
    showWaypoints: Boolean,
    selectingTiles: Boolean,
    onTogglePlayers: () -> Unit,
    onToggleWaypoints: () -> Unit,
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
private fun TileStatus(
    state: TileDownloadState,
    tileTemplate: String?,
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
        TileDownloadState.Idle -> if (tileTemplate != null) StatusPill("Offline overview", AccentCyan, modifier) else Unit
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
                textOffset(arrayOf(0f, 1.3f)),
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

private fun emptyGeoJsonSource(id: String): GeoJsonSource = GeoJsonSource(id, emptyFeatureCollection())
private fun emptyFeatureCollection(): FeatureCollection = FeatureCollection.fromFeatures(emptyList())

private fun mapStyleJson(@androidx.annotation.ColorInt bgArgb: Int, tileTemplate: String?, isDark: Boolean): String {
    val bgHex = "#%06X".format(bgArgb and 0xFFFFFF)
    val tileSource = tileTemplate?.replace("\\", "\\\\")?.replace("\"", "\\\"")?.let { url ->
        ",\n        \"field-tiles\": { \"type\": \"raster\", \"tiles\": [\"$url\"], \"tileSize\": 256 }"
    }.orEmpty()
    val tileLayer = if (tileTemplate != null) {
        ",\n        { \"id\": \"field-tiles\", \"type\": \"raster\", \"source\": \"field-tiles\" }"
    } else ""
    return """
    {
      "version": 8,
      "name": "AirTAK Field",
      "sources": {
        "blank": { "type": "geojson", "data": { "type": "FeatureCollection", "features": [] } }
        $tileSource
      },
      "layers": [
        { "id": "background", "type": "background", "paint": { "background-color": "$bgHex" } }
        $tileLayer
      ]
    }
    """.trimIndent()
}

private const val OWN_SOURCE = "own-position-source"
private const val OWN_LAYER = "own-position-layer"
private const val REMOTE_SOURCE = "remote-node-source"
private const val REMOTE_LAYER = "remote-node-layer"
private const val REMOTE_LABEL_LAYER = "remote-node-label-layer"
private const val WAYPOINT_SOURCE = "waypoint-source"
private const val WAYPOINT_LAYER = "waypoint-layer"
private const val WAYPOINT_LABEL_LAYER = "waypoint-label-layer"
private const val TILE_SELECTION_SOURCE = "tile-selection-source"
private const val TILE_SELECTION_FILL_LAYER = "tile-selection-fill-layer"
private const val TILE_SELECTION_LINE_LAYER = "tile-selection-line-layer"