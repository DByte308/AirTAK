// MASH Android — ui/map/MapScreen.kt
// Planning: §11.4, §11.5 | Roadmap: 3.1, 3.4, 3.5
//
// MapLibre GL map showing:
//   - Own GPS position (pulsing blue dot)
//   - Node pins with callsign labels
//   - Zone overlays (colored polygons from MASH_ZONE packets)
//   - Objective markers
//   - User waypoints (local only, never broadcast)
//
// Tile source: offline MBTiles managed by TileRepository (§11.5).

package com.mash.ui.map

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun MapScreen(viewModel: MapViewModel = hiltViewModel()) {
    // TODO Phase 3.1: Embed MapLibre MapView in AndroidView composable.
    // TODO Phase 3.4: Subscribe to viewModel.nodes, viewModel.zones for overlay updates.
    // TODO Phase 3.5: Display own position and allow waypoint placement on long-press.
}
