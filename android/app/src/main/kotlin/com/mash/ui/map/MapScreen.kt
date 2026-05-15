// AirTAK Android — ui/map/MapScreen.kt
// Planning: §11.4, §11.5 | Roadmap: 3.1, 3.4, 3.5
//
// MapLibre GL 11 map showing node positions.
// Node pins rendered via a GeoJSON source + SymbolLayer (core SDK, no annotation plugin needed).

package com.airtak.ui.map

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.airtak.data.db.NodeEntity
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

private const val MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
private const val NODES_SOURCE_ID = "airtak-nodes"
private const val NODES_LAYER_ID  = "airtak-nodes-layer"

@Composable
fun MapScreen(viewModel: MapViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val mapState = remember { MapState() }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                MapLibre.getInstance(ctx)
                MapView(ctx).also { mapView ->
                    mapState.mapView = mapView
                    mapView.getMapAsync { map ->
                        mapState.map = map
                        map.setStyle(Style.Builder().fromUri(MAP_STYLE_URL)) { style ->
                            // Add an empty GeoJSON source for node positions.
                            style.addSource(GeoJsonSource(NODES_SOURCE_ID, FeatureCollection.fromFeatures(emptyList())))
                            // Symbol layer with callsign text labels and default marker icon.
                            style.addLayer(
                                SymbolLayer(NODES_LAYER_ID, NODES_SOURCE_ID)
                                    .withProperties(
                                        PropertyFactory.iconImage("marker-15"),
                                        PropertyFactory.iconAllowOverlap(true),
                                        PropertyFactory.textField("{callsign}"),
                                        PropertyFactory.textOffset(arrayOf(0f, 1.5f)),
                                        PropertyFactory.textAllowOverlap(true),
                                    )
                            )
                            mapState.style = style
                            mapState.updateMarkers(uiState.nodes)
                        }
                        map.cameraPosition = CameraPosition.Builder()
                            .target(LatLng(51.5, -0.1))
                            .zoom(14.0)
                            .build()
                    }
                }
            },
            update = { /* MapView managed by MapState */ },
            modifier = Modifier.fillMaxSize(),
        )

        LaunchedEffect(uiState.nodes) {
            mapState.updateMarkers(uiState.nodes)
        }

        if (uiState.nodes.isNotEmpty()) {
            Surface(
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                color    = MaterialTheme.colorScheme.secondaryContainer,
                shape    = MaterialTheme.shapes.small,
            ) {
                Text(
                    text     = "${uiState.nodes.size} node${if (uiState.nodes.size == 1) "" else "s"}",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    fontSize = 12.sp,
                )
            }
        }
    }
}

private class MapState {
    var mapView: MapView? = null
    var map: MapLibreMap? = null
    var style: Style? = null

    fun updateMarkers(nodes: List<NodeEntity>) {
        val src = style?.getSourceAs<GeoJsonSource>(NODES_SOURCE_ID) ?: return
        val features = nodes.mapNotNull { node ->
            val lat = node.latE7 ?: return@mapNotNull null
            val lon = node.lonE7 ?: return@mapNotNull null
            Feature.fromGeometry(
                Point.fromLngLat(lon / 1e7, lat / 1e7),
                com.google.gson.JsonObject().also { props ->
                    props.addProperty("callsign", node.callsign)
                    props.addProperty("rssi", node.rssi)
                },
            )
        }
        src.setGeoJson(FeatureCollection.fromFeatures(features))
    }
}
