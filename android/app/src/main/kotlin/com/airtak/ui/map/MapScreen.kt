// AirTAK Android — ui/map/MapScreen.kt
// Planning: §11.5
// Roadmap: 3.6
//
// MapLibre GL Android wrapper. For Phase 3 we render an offline-safe map:
//
//   - background layer in a tactical dark/light color
//   - GeoJsonSource for own position (updated from DEVICE_STATUS.gps)
//   - optional raster source if the user has configured a tile URL
//
// Phase 4 will attach remote-node pins; Phase 5 will attach zone polygons and
// objective markers. These layers will plug into the same MapboxMap instance
// owned by MapScreenState — no further restructure required.

package com.airtak.ui.map

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.airtak.ui.theme.AccentCyan
import com.airtak.ui.theme.DarkBackground
import com.airtak.ui.theme.LightBackground
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

@Composable
fun MapScreen(viewModel: MapViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val gps by viewModel.ownPosition.collectAsState()
    val isDark = MaterialTheme.colorScheme.background == DarkBackground
    val bgColor = (if (isDark) DarkBackground else LightBackground).toArgb()

    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply {
            getMapAsync { map ->
                map.setStyle(Style.Builder().fromJson(blankStyleJson(bgColor)))
                map.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(0.0, 0.0))
                    .zoom(2.0)
                    .build()
            }
        }
    }

    // Bind MapView lifecycle to host composable.
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START   -> mapView.onStart()
                Lifecycle.Event.ON_RESUME  -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE   -> mapView.onPause()
                Lifecycle.Event.ON_STOP    -> mapView.onStop()
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

    // Recenter when own GPS becomes available for the first time, then never
    // again automatically (we don't fight a user pan).
    DisposableEffect(gps != null) {
        val pos = gps
        if (pos != null) {
            mapView.getMapAsync { it.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(pos.first, pos.second), 14.0)) }
        }
        onDispose {}
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        // Recenter FAB.
        FloatingActionButton(
            onClick = {
                val pos = gps ?: return@FloatingActionButton
                mapView.getMapAsync {
                    it.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(pos.first, pos.second), 14.0))
                }
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
}

private fun blankStyleJson(@androidx.annotation.ColorInt bgArgb: Int): String {
    val hex = "#%06X".format(bgArgb and 0xFFFFFF)
    return """
    {
      "version": 8,
      "name": "AirTAK Tactical",
      "sources": {},
      "sprite": "",
      "glyphs": "",
      "layers": [
        { "id": "background", "type": "background", "paint": { "background-color": "$hex" } }
      ]
    }
    """.trimIndent()
}
