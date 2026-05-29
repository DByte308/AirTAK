// AirTAK Android — data/tiles/TileRepository.kt

package com.airtak.data.tiles

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.airtak.data.prefs.AppPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.request.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.PI
import kotlin.math.pow
import com.airtak.config.constants.MAP_MERCATOR_MAX_LAT
import com.airtak.config.constants.MAP_MERCATOR_MAX_LON
import com.airtak.config.constants.MAP_MERCATOR_MIN_LAT
import com.airtak.config.constants.MAP_MERCATOR_MIN_LON
import com.airtak.config.constants.MAP_MAX_TILES_PER_DOWNLOAD
import com.airtak.config.constants.NETWORK_DEFAULT_TILE_URL
import com.airtak.config.constants.UI_TILE_CACHE_MAX_AGE_SEC
import com.airtak.config.constants.ASSET_WORLD_OVERVIEW_FILE
import kotlin.math.tan
import javax.inject.Inject
import javax.inject.Singleton

data class TileBounds(
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,
)

sealed interface TileDownloadState {
    data object Idle : TileDownloadState
    data class Running(val completed: Int, val total: Int) : TileDownloadState
    data class Complete(val fileName: String, val downloaded: Int, val skipped: Int) : TileDownloadState
    data class Failed(val message: String) : TileDownloadState
}

@Singleton
class TileRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: AppPreferences,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = HttpClient(Android)
    private val tileDir = File(context.filesDir, "tiles")

    private val _downloadState = MutableStateFlow<TileDownloadState>(TileDownloadState.Idle)
    val downloadState: StateFlow<TileDownloadState> = _downloadState.asStateFlow()

    private val _tileTemplate = MutableStateFlow<String?>(configuredOrDefaultTileUrl())
    val tileTemplate: StateFlow<String?> = _tileTemplate.asStateFlow()

    private val _localTileTemplate = MutableStateFlow<String?>(null)
    val localTileTemplate: StateFlow<String?> = _localTileTemplate.asStateFlow()

    private val _localTileMaxZoom = MutableStateFlow<Int?>(null)
    val localTileMaxZoom: StateFlow<Int?> = _localTileMaxZoom.asStateFlow()

    private val _configuredTileTemplate = MutableStateFlow(configuredOrDefaultTileUrl())
    val configuredTileTemplate: StateFlow<String> = _configuredTileTemplate.asStateFlow()

    @Volatile private var server: ServerSocket? = null
    @Volatile private var mbtilesFiles: List<File> = emptyList()
    @Volatile private var mbtilesRevision: Long = 0L

    init {
        tileDir.mkdirs()
        seedBundledOverviewTiles()
        refreshLocalTiles()
        scope.launch {
            prefs.observe().collect {
                _configuredTileTemplate.value = configuredOrDefaultTileUrl()
                _tileTemplate.value = configuredOrDefaultTileUrl()
            }
        }
    }

    private fun seedBundledOverviewTiles() {
        val target = File(tileDir, WORLD_OVERVIEW_FILE)
        runCatching {
            val temp = File(tileDir, "$WORLD_OVERVIEW_FILE.tmp")
            context.assets.open("tiles/$WORLD_OVERVIEW_FILE").use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            if (temp.length() > 0L) {
                target.delete()
                temp.renameTo(target)
            } else {
                temp.delete()
            }
        }
    }

    fun download(bounds: TileBounds, minZoom: Int, maxZoom: Int, urlTemplateOverride: String? = null) {
        val urlTemplate = urlTemplateOverride?.trim()?.ifBlank { null }
            ?: configuredOrDefaultTileUrl()
        if (!urlTemplate.contains("{z}") || !urlTemplate.contains("{x}") || !urlTemplate.contains("{y}")) {
            _downloadState.value = TileDownloadState.Failed("Set a tile URL with {z}, {x}, and {y}.")
            return
        }
        if (urlTemplate != prefs.tileServerUrl()?.trim().orEmpty()) {
            prefs.setTileServerUrl(urlTemplate)
            _configuredTileTemplate.value = urlTemplate
        }
        val zMin = minZoom.coerceIn(0, 22)
        val zMax = maxZoom.coerceIn(zMin, 22)
        scope.launch {
            runCatching { downloadInternal(bounds.normalized(), zMin, zMax, urlTemplate) }
                .onFailure { _downloadState.value = TileDownloadState.Failed(it.message ?: "Tile download failed") }
        }
    }

    private suspend fun downloadInternal(bounds: TileBounds, minZoom: Int, maxZoom: Int, urlTemplate: String) {
        tileDir.mkdirs()
        val file = File(tileDir, "field.mbtiles")
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL("CREATE TABLE IF NOT EXISTS metadata (name TEXT PRIMARY KEY, value TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB, PRIMARY KEY (zoom_level, tile_column, tile_row))")
        setMetadata(db, "name", "AirTAK Field")
        setMetadata(db, "format", "png")
        setMetadata(db, "bounds", "${bounds.minLon},${bounds.minLat},${bounds.maxLon},${bounds.maxLat}")
        setMetadata(db, "minzoom", minZoom.toString())
        setMetadata(db, "maxzoom", maxZoom.toString())

        val tiles = (minZoom..maxZoom).flatMap { z -> tileRange(bounds, z) }
        if (tiles.size > MAX_TILES_PER_DOWNLOAD) {
            db.close()
            _downloadState.value = TileDownloadState.Failed("Selected area is ${tiles.size} tiles; narrow the map or zoom range.")
            return
        }

        var downloaded = 0
        var skipped = 0
        tiles.forEachIndexed { index, tile ->
            _downloadState.value = TileDownloadState.Running(index, tiles.size)
            val tmsY = (1 shl tile.z) - 1 - tile.y
            if (hasTile(db, tile.z, tile.x, tmsY)) {
                skipped++
                return@forEachIndexed
            }
            val url = urlTemplate
                .replace("{z}", tile.z.toString())
                .replace("{x}", tile.x.toString())
                .replace("{y}", tile.y.toString())
            val data: ByteArray = client.get(url).body()
            val values = ContentValues().apply {
                put("zoom_level", tile.z)
                put("tile_column", tile.x)
                put("tile_row", tmsY)
                put("tile_data", data)
            }
            db.insertWithOnConflict("tiles", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            downloaded++
        }
        db.close()
        file.setLastModified(System.currentTimeMillis())
        refreshLocalTiles()
        _downloadState.value = TileDownloadState.Complete(file.name, downloaded, skipped)
    }

    private fun refreshLocalTiles() {
        mbtilesFiles = tileDir.listFiles { f -> f.extension.equals("mbtiles", ignoreCase = true) }
            ?.sortedWith(
                compareBy<File> { it.name == WORLD_OVERVIEW_FILE }
                    .thenByDescending { it.lastModified() },
            )
            .orEmpty()
        mbtilesRevision = mbtilesFiles.maxOfOrNull { it.lastModified() } ?: 0L
        _localTileMaxZoom.value = downloadedTileMaxZoom(mbtilesFiles)
        if (mbtilesFiles.isNotEmpty()) ensureServer()
        _localTileTemplate.value = localTileUrl()
    }

    private fun downloadedTileMaxZoom(files: List<File>): Int? {
        val overviewMaxZoom = files.firstOrNull { it.name == WORLD_OVERVIEW_FILE }?.let { readMaxZoom(it) }
        return files
            .filterNot { it.name == WORLD_OVERVIEW_FILE }
            .mapNotNull { readMaxZoom(it) }
            .maxOrNull()
            ?.takeIf { overviewMaxZoom == null || it > overviewMaxZoom }
    }

    private fun readMaxZoom(file: File): Int? = runCatching {
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            db.rawQuery("SELECT MAX(zoom_level) FROM tiles", emptyArray()).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getInt(0) else null
            }
        } finally {
            db.close()
        }
    }.getOrNull()

    private fun localTileUrl(): String? {
        return server?.localPort?.takeIf { it > 0 }?.let { port ->
            if (mbtilesFiles.isNotEmpty()) "http://127.0.0.1:$port/tiles/{z}/{x}/{y}.png?v=$mbtilesRevision" else null
        }
    }

    private fun configuredOrDefaultTileUrl(): String =
        prefs.tileServerUrl()?.trim()?.takeIf { it.isNotBlank() } ?: DEFAULT_TILE_URL

    private fun ensureServer() {
        if (server != null) return
        val socket = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
        server = socket
        scope.launch {
            while (!socket.isClosed) {
                val clientSocket = runCatching { socket.accept() }.getOrNull() ?: break
                launch { serveTile(clientSocket) }
            }
        }
    }

    private suspend fun serveTile(socket: Socket) = withContext(Dispatchers.IO) {
        socket.use { s ->
            val input = s.getInputStream().bufferedReader()
            val requestLine = input.readLine().orEmpty()
            while (true) {
                val header = input.readLine() ?: break
                if (header.isEmpty()) break
            }
            val parts = requestLine.split(' ')
            val path = parts.getOrNull(1).orEmpty().substringBefore('?')
            val match = TILE_PATH.matchEntire(path)
            val bytes = if (match != null) {
                val z = match.groupValues[1].toInt()
                val x = match.groupValues[2].toInt()
                val y = match.groupValues[3].toInt()
                readTile(z, x, y)
            } else null
            val out = s.getOutputStream()
            if (bytes == null) {
                out.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray())
            } else {
                out.write("HTTP/1.1 200 OK\r\nContent-Type: ${bytes.contentType()}\r\nCache-Control: max-age=$UI_TILE_CACHE_MAX_AGE_SEC\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray())
                out.write(bytes)
            }
            out.flush()
        }
    }

    private fun ByteArray.contentType(): String = when {
        size >= 8 && this[0] == 0x89.toByte() && this[1] == 0x50.toByte() && this[2] == 0x4E.toByte() -> "image/png"
        size >= 3 && this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte() && this[2] == 0xFF.toByte() -> "image/jpeg"
        size >= 12 && copyOfRange(0, 4).decodeToString() == "RIFF" && copyOfRange(8, 12).decodeToString() == "WEBP" -> "image/webp"
        else -> "application/octet-stream"
    }

    private fun readTile(z: Int, x: Int, y: Int): ByteArray? {
        val tmsY = (1 shl z) - 1 - y
        for (file in mbtilesFiles) {
            val bytes = runCatching {
                val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                try {
                    db.rawQuery(
                        "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=? LIMIT 1",
                        arrayOf(z.toString(), x.toString(), tmsY.toString()),
                    ).use { cursor ->
                        if (cursor.moveToFirst()) cursor.getBlob(0) else null
                    }
                } finally {
                    db.close()
                }
            }.getOrNull()
            if (bytes != null) return bytes
        }
        return null
    }

    private fun hasTile(db: SQLiteDatabase, z: Int, x: Int, tmsY: Int): Boolean =
        db.rawQuery(
            "SELECT 1 FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=? LIMIT 1",
            arrayOf(z.toString(), x.toString(), tmsY.toString()),
        ).use { it.moveToFirst() }

    private fun setMetadata(db: SQLiteDatabase, name: String, value: String) {
        val cv = ContentValues().apply {
            put("name", name)
            put("value", value)
        }
        db.insertWithOnConflict("metadata", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun tileRange(bounds: TileBounds, zoom: Int): List<TileCoord> {
        val topLeft = latLonToTile(bounds.maxLat, bounds.minLon, zoom)
        val bottomRight = latLonToTile(bounds.minLat, bounds.maxLon, zoom)
        return (topLeft.x..bottomRight.x).flatMap { x ->
            (topLeft.y..bottomRight.y).map { y -> TileCoord(zoom, x, y) }
        }
    }

    private fun latLonToTile(lat: Double, lon: Double, zoom: Int): TileXY {
        val clampedLat = lat.coerceIn(MAP_MERCATOR_MIN_LAT, MAP_MERCATOR_MAX_LAT)
        val clampedLon = lon.coerceIn(MAP_MERCATOR_MIN_LON, MAP_MERCATOR_MAX_LON)
        val n = 2.0.pow(zoom)
        val x = ((clampedLon + 180.0) / 360.0 * n).toInt().coerceIn(0, n.toInt() - 1)
        val latRad = Math.toRadians(clampedLat)
        val y = ((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n).toInt()
            .coerceIn(0, n.toInt() - 1)
        return TileXY(x, y)
    }

    private fun TileBounds.normalized(): TileBounds = TileBounds(
        minLat = minOf(minLat, maxLat).coerceIn(MAP_MERCATOR_MIN_LAT, MAP_MERCATOR_MAX_LAT),
        minLon = minOf(minLon, maxLon).coerceIn(MAP_MERCATOR_MIN_LON, MAP_MERCATOR_MAX_LON),
        maxLat = maxOf(minLat, maxLat).coerceIn(MAP_MERCATOR_MIN_LAT, MAP_MERCATOR_MAX_LAT),
        maxLon = maxOf(minLon, maxLon).coerceIn(MAP_MERCATOR_MIN_LON, MAP_MERCATOR_MAX_LON),
    )

    private data class TileCoord(val z: Int, val x: Int, val y: Int)
    private data class TileXY(val x: Int, val y: Int)

    private companion object {
        // ── Canonical definitions in config/constants/AppConstants.kt & MapConstants.kt ─
        val TILE_PATH = Regex("/tiles/(\\d+)/(\\d+)/(\\d+)\\.png")
        const val DEFAULT_TILE_URL = NETWORK_DEFAULT_TILE_URL   // Canonical: AppConstants
        // ── Canonical definition in config/constants/AppConstants.kt ───────────
        const val WORLD_OVERVIEW_FILE = ASSET_WORLD_OVERVIEW_FILE
        const val MAX_TILES_PER_DOWNLOAD = MAP_MAX_TILES_PER_DOWNLOAD  // Canonical: MapConstants
    }
}