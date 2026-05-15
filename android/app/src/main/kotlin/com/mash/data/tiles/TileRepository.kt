// AirTAK Android — data/tiles/TileRepository.kt
// Planning: §11.5 | Roadmap: T.6, 3.1
//
// Manages offline MBTiles SQLite databases for MapLibre.
// Tiles can be downloaded by tools/tile_fetch.py and loaded by URI.
// Supports multiple MBTiles files (e.g., one per operation area).
// Configurable tile server URL (set via DeviceConfig or Settings UI).

package com.airtak.data.tiles

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TileRepository @Inject constructor() {

    // TODO Phase 3.1: Return a list of available MBTiles file paths from internal storage.
    fun availableTileFiles(): List<String> = emptyList()

    // TODO Phase 3.1: Return the MapLibre TileProvider URI for the selected MBTiles file.
    fun tileProviderUri(filePath: String): String = "mbtiles://$filePath"

    // TODO T.6: Download tiles for a bounding box from the configured tile server.
    suspend fun downloadTiles(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double,
                              minZoom: Int, maxZoom: Int, tileServerUrl: String) {
        // TODO T.6
    }
}
