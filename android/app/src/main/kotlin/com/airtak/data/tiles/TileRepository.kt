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
import com.airtak.net.AirTakHttp
import com.airtak.net.BulkDownloadVerdict
import com.airtak.net.LiveFetchVerdict
import com.airtak.net.RemoteTileFetcher
import com.airtak.net.RequestBudgets
import com.airtak.net.TileProviderPolicy
import com.airtak.net.TileProxyHandler
import com.airtak.net.TileRoute
import com.airtak.config.constants.UI_TILE_CACHE_MAX_AGE_SEC
import com.airtak.config.constants.ASSET_WORLD_OVERVIEW_FILE
import kotlin.math.tan
import javax.inject.Inject
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
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

    // One budget per provider, shared by the proxy and the bulk downloader below. Two independent
    // budgets for the same provider would add up, so this instance is deliberately owned here and
    // handed to both paths rather than each constructing its own.
    private val budgets = RequestBudgets()
    private val remoteTiles = RemoteTileFetcher(budgets)

    // Redirects are refused so a 3xx cannot move a fetch onto a host the policy already refused.
    private val client = AirTakHttp.newClient(followRedirects = false)

    private val tileDir = File(context.filesDir, "tiles")

    @Volatile private var downloadJob: Job? = null

    private val _downloadState = MutableStateFlow<TileDownloadState>(TileDownloadState.Idle)
    val downloadState: StateFlow<TileDownloadState> = _downloadState.asStateFlow()

    private val _tileTemplate = MutableStateFlow<String?>(remoteProxyUrl())
    val tileTemplate: StateFlow<String?> = _tileTemplate.asStateFlow()

    private val _localTileTemplate = MutableStateFlow<String?>(null)
    val localTileTemplate: StateFlow<String?> = _localTileTemplate.asStateFlow()

    private val _localTileMaxZoom = MutableStateFlow<Int?>(null)
    val localTileMaxZoom: StateFlow<Int?> = _localTileMaxZoom.asStateFlow()

    private val _configuredTileTemplate = MutableStateFlow(configuredTileUrlOrNull() ?: "")
    val configuredTileTemplate: StateFlow<String> = _configuredTileTemplate.asStateFlow()

    @Volatile private var server: ServerSocket? = null
    @Volatile private var mbtilesFiles: List<File> = emptyList()
    @Volatile private var mbtilesRevision: Long = 0L

    init {
        tileDir.mkdirs()
        seedBundledOverviewTiles()
        // Started before any tile exists: the proxy has to be reachable with zero local coverage,
        // otherwise a configured provider has no path that carries identification or pacing.
        ensureServer()
        refreshLocalTiles()
        scope.launch {
            prefs.observe().collect {
                _configuredTileTemplate.value = configuredTileUrlOrNull() ?: ""
                _tileTemplate.value = remoteProxyUrl()
            }
        }
    }

    private fun seedBundledOverviewTiles() {
        val target = File(tileDir, ASSET_WORLD_OVERVIEW_FILE)
        runCatching {
            val temp = File(tileDir, "$ASSET_WORLD_OVERVIEW_FILE.tmp")
            context.assets.open("tiles/$ASSET_WORLD_OVERVIEW_FILE").use { input ->
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

    /** Cancels an in-flight download. Already-stored tiles are left valid, never truncated. */
    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _downloadState.value = TileDownloadState.Failed("Download cancelled.")
    }

    fun isDownloading(): Boolean = downloadJob?.isActive == true

    fun download(bounds: TileBounds, minZoom: Int, maxZoom: Int, urlTemplateOverride: String? = null) {
        val urlTemplate = (urlTemplateOverride?.trim()?.ifBlank { null } ?: configuredTileUrlOrNull()).orEmpty()
        if (urlTemplate.isBlank()) {
            _downloadState.value = TileDownloadState.Failed(
                "No tile server configured. Set one in Settings > App. AirTAK ships no default " +
                    "provider so that bulk downloading is always a deliberate choice.",
            )
            return
        }
        when (val verdict = TileProviderPolicy.verdictForBulkDownload(urlTemplate)) {
            is BulkDownloadVerdict.Allowed -> Unit
            is BulkDownloadVerdict.Prohibited -> {
                _downloadState.value = TileDownloadState.Failed(verdict.reason)
                return
            }
            is BulkDownloadVerdict.Invalid -> {
                _downloadState.value = TileDownloadState.Failed(verdict.reason)
                return
            }
        }
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
        downloadJob?.cancel()
        downloadJob = scope.launch {
            runCatching { downloadInternal(bounds.normalized(), zMin, zMax, urlTemplate) }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    _downloadState.value = TileDownloadState.Failed(it.message ?: "Tile download failed")
                }
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
        if (tiles.size > MAP_MAX_TILES_PER_DOWNLOAD) {
            db.close()
            _downloadState.value = TileDownloadState.Failed("Selected area is ${tiles.size} tiles; narrow the map or zoom range.")
            return
        }

        var downloaded = 0
        var skipped = 0
        var refused = 0
        tiles.forEachIndexed { index, tile ->
            coroutineContext.ensureActive()
            _downloadState.value = TileDownloadState.Running(index, tiles.size)
            // Validated before the shift. shl masks the shift by 31, so an unbounded z produced a
            // wrong row rather than an error.
            if (tile.z < 0 || tile.z > TileProxyHandler.MAX_SUPPORTED_ZOOM) {
                refused++
                return@forEachIndexed
            }
            val span = 1L shl tile.z
            if (tile.x < 0 || tile.x >= span || tile.y < 0 || tile.y >= span) {
                refused++
                return@forEachIndexed
            }
            val tmsY = ((1L shl tile.z) - 1 - tile.y).toInt()
            if (hasTile(db, tile.z, tile.x, tmsY)) {
                skipped++
                return@forEachIndexed
            }
            val result = remoteTiles.fetch(urlTemplate, tile.z, tile.x, tile.y, TileProxyHandler.MAX_SUPPORTED_ZOOM)
            val data: ByteArray = when (result) {
                is RemoteTileFetcher.Result.Fetched -> {
                    // A provider that forbids persistence must not end up in an offline archive.
                    // Abort rather than keep fetching thousands of tiles into an unusable package.
                    if (result.directives.persistenceProhibited) {
                        db.close()
                        _downloadState.value = TileDownloadState.Failed(
                            "Provider sent Cache-Control: no-store at tile ${tile.z}/${tile.x}/${tile.y}. " +
                                "Offline persistence is not permitted by this provider, so the download " +
                                "was stopped. Previously stored tiles are untouched.",
                        )
                        return
                    }
                    result.bytes
                }
                is RemoteTileFetcher.Result.NotFound -> return@forEachIndexed
                is RemoteTileFetcher.Result.Refused -> {
                    db.close()
                    _downloadState.value = TileDownloadState.Failed(result.reason)
                    return
                }
                is RemoteTileFetcher.Result.NoProvider -> {
                    db.close()
                    _downloadState.value = TileDownloadState.Failed(result.reason)
                    return
                }
                is RemoteTileFetcher.Result.Failed -> return@forEachIndexed
            }
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
        if (refused > 0) {
            _downloadState.value = TileDownloadState.Failed(
                "Completed but $refused coordinates were outside the supported grid and were skipped.",
            )
        }
    }

    private fun refreshLocalTiles() {
        mbtilesFiles = tileDir.listFiles { f -> f.extension.equals("mbtiles", ignoreCase = true) }
            ?.sortedWith(
                compareBy<File> { it.name == ASSET_WORLD_OVERVIEW_FILE }
                    .thenByDescending { it.lastModified() },
            )
            .orEmpty()
        mbtilesRevision = mbtilesFiles.maxOfOrNull { it.lastModified() } ?: 0L
        _localTileMaxZoom.value = downloadedTileMaxZoom(mbtilesFiles)
        if (mbtilesFiles.isNotEmpty()) ensureServer()
        _localTileTemplate.value = localTileUrl()
    }

    private fun downloadedTileMaxZoom(files: List<File>): Int? {
        val overviewMaxZoom = files.firstOrNull { it.name == ASSET_WORLD_OVERVIEW_FILE }?.let { readMaxZoom(it) }
        return files
            .filterNot { it.name == ASSET_WORLD_OVERVIEW_FILE }
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

    /**
     * The user-configured provider, or null. There is deliberately no default: shipping a public
     * community tile server as the default made every user an accidental bulk client of shared
     * infrastructure they never chose.
     */
    private fun configuredTileUrlOrNull(): String? =
        prefs.tileServerUrl()?.trim()?.takeIf { it.isNotBlank() }

    /**
     * Loopback URL MapLibre should use for remote tiles, or null when there is no usable provider.
     * MapLibre 11 issues tile requests through its own native file source and exposes no header
     * hook, so pointing it straight at a provider would bypass identification, pacing, caching and
     * cancellation entirely. Routing it here is what keeps those guarantees real.
     */
    private fun remoteProxyUrl(): String? {
        val port = server?.localPort?.takeIf { it > 0 } ?: return null
        val provider = configuredTileUrlOrNull() ?: return null
        if (TileProviderPolicy.verdictForLiveFetch(provider) !is LiveFetchVerdict.Allowed) return null
        return "http://127.0.0.1:$port/remote/{z}/{x}/{y}.png"
    }

    private fun ensureServer() {
        if (server != null) return
        // Loopback only, bounded accept queue. Backlog is finite so a flood cannot grow an
        // unbounded pending set inside the kernel on a device.
        val socket = ServerSocket(SERVER_ACCEPT_BACKLOG, 0, InetAddress.getByName("127.0.0.1"))
        server = socket
        scope.launch {
            while (!socket.isClosed) {
                val clientSocket = runCatching { socket.accept() }.getOrNull() ?: break
                launch { serveTile(clientSocket) }
            }
        }
    }

    /**
     * Serve one loopback request.
     *
     * Bounded on every axis a network-facing listener has to be: the request line and header block
     * are read with caps and the connection is closed on a parse error rather than buffered, the
     * socket has a timeout so a stalled client cannot hold a handler, and only the routes
     * TileProxyHandler recognises are served. The response is a tile or a status, never a general
     * proxy answer, and the request can never name a target host.
     */
    private suspend fun serveTile(socket: Socket) = withContext(Dispatchers.IO) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = SERVER_SOCKET_TIMEOUT_MS
            val input = socket.getInputStream()
            val requestLine = input.readCappedLine(TileProxyHandler.MAX_REQUEST_LINE_BYTES)
            val headerNames = mutableListOf<String>()
            var oversized = false
            var headerLines = 0
            while (true) {
                val line = input.readCappedLine(TileProxyHandler.MAX_HEADER_BYTES) ?: break
                if (line.isEmpty()) break
                headerLines++
                if (headerLines > TileProxyHandler.MAX_HEADER_LINES) { oversized = true; break }
                headerNames += line.substringBefore(':').trim()
            }
            if (requestLine == null || oversized) {
                writeStatus(socket, if (oversized) 431 else 400, headOnly = false)
                return@withContext
            }

            when (val route = TileProxyHandler.route(requestLine, headerNames, TileProxyHandler.MAX_SUPPORTED_ZOOM)) {
                is TileRoute.Reject -> writeStatus(socket, route.status, headOnly = false)

                is TileRoute.Local -> {
                    val bytes = readTile(route.z, route.x, route.y)
                    if (bytes == null) writeStatus(socket, 404, route.headOnly) else writeTile(socket, bytes, route.headOnly)
                }

                is TileRoute.Remote -> when (
                    val result = remoteTiles.fetch(
                        configuredTileUrlOrNull(),
                        route.z,
                        route.x,
                        route.y,
                        TileProxyHandler.MAX_SUPPORTED_ZOOM,
                    )
                ) {
                    is RemoteTileFetcher.Result.Fetched -> writeTile(socket, result.bytes, route.headOnly)
                    is RemoteTileFetcher.Result.NotFound -> writeStatus(socket, 404, route.headOnly)
                    is RemoteTileFetcher.Result.Refused -> writeStatus(socket, 503, route.headOnly)
                    is RemoteTileFetcher.Result.NoProvider -> writeStatus(socket, 503, route.headOnly)
                    is RemoteTileFetcher.Result.Failed -> writeStatus(socket, 502, route.headOnly)
                }
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            // A malformed or aborted request closes the connection. Nothing is buffered and no
            // partial response is left for the client to interpret.
        } finally {
            runCatching { socket.close() }
        }
    }

    /** Read one CRLF-terminated line, refusing anything longer than [maxBytes] instead of growing. */
    private fun java.io.InputStream.readCappedLine(maxBytes: Int): String? {
        val bytes = ByteArrayOutputStream(maxOf(64, minOf(maxBytes, 1024)))
        var terminated = false
        while (true) {
            val b = read()
            if (b == -1) break
            if (b == '\n'.code) { terminated = true; break }
            if (bytes.size() >= maxBytes) return null
            bytes.write(b)
        }
        if (!terminated && bytes.size() == 0) return null
        return bytes.toByteArray().toString(Charsets.US_ASCII).trimEnd('\r')
    }

    private fun writeStatus(socket: Socket, status: Int, headOnly: Boolean) {
        val reason = when (status) {
            400 -> "Bad Request"; 404 -> "Not Found"; 405 -> "Method Not Allowed"
            414 -> "URI Too Long"; 431 -> "Header Fields Too Large"
            501 -> "Not Implemented"; 502 -> "Bad Gateway"; 503 -> "Service Unavailable"
            else -> "Error"
        }
        val head = "HTTP/1.1 $status $reason\r\nConnection: close\r\nContent-Length: 0\r\n\r\n"
        socket.getOutputStream().use { it.write(head.toByteArray()); it.flush() }
        if (headOnly) return
    }

    private fun writeTile(socket: Socket, bytes: ByteArray, headOnly: Boolean) {
        // No Cache-Control is asserted here. The app previously served an invented max-age, which
        // claimed a freshness it had never verified against any provider.
        val head = "HTTP/1.1 200 OK\r\nContent-Type: ${bytes.contentType()}\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().use { out ->
            out.write(head.toByteArray())
            if (!headOnly) out.write(bytes)
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
        /** Finite backlog so a flood cannot grow an unbounded pending set in the kernel. */
        const val SERVER_ACCEPT_BACKLOG = 16

        /** A stalled or hostile client must not hold a handler coroutine open. */
        const val SERVER_SOCKET_TIMEOUT_MS = 5_000

        val TILE_PATH = Regex("/tiles/(\\d+)/(\\d+)/(\\d+)\\.png")
    }
}