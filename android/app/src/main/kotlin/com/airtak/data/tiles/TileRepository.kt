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
import com.airtak.net.LocalTileReader
import com.airtak.net.RemoteTileOutcome
import com.airtak.net.RemoteTileSource
import com.airtak.net.TileServer
import com.airtak.net.AirTakHttp
import com.airtak.net.BulkDownloadVerdict
import com.airtak.net.LiveFetchVerdict
import com.airtak.net.RemoteTileFetcher
import com.airtak.net.RequestBudgets
import com.airtak.net.TileIdentity
import com.airtak.net.TileProviderPolicy
import com.airtak.net.TileProxyHandler
import com.airtak.net.TileRoute
import com.airtak.config.constants.UI_TILE_CACHE_MAX_AGE_SEC
import com.airtak.config.constants.ASSET_WORLD_OVERVIEW_FILE
import kotlin.math.tan
import javax.inject.Inject
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import javax.inject.Singleton

data class TileBounds(
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,
)

/** Raised to stop a download for a stated reason while leaving stored tiles valid. */
private class TileDownloadAborted(message: String) : Exception(message)

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
    /** Serialises downloads so a cancelled job and its replacement cannot write one file at once. */
    private val downloadMutex = Mutex()

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

    /** The shipped loopback listener. TileRepository owns its lifecycle. */
    private val tileServer: TileServer by lazy {
        TileServer(scope, LocalTileReader { z, x, y -> readTile(z, x, y) }, remoteSource)
    }

    @Volatile private var mbtilesFiles: List<File> = emptyList()

    /** Recomputed whenever the archives or the configured provider change. */
    @Volatile private var servingFiles: List<File> = emptyList()
    @Volatile private var mbtilesRevision: Long = 0L

    init {
        tileDir.mkdirs()
        seedBundledOverviewTiles()
        // Started before any tile exists: the proxy has to be reachable with zero local coverage,
        // otherwise a configured provider has no path that carries identification or pacing.
        ensureServer()
        _tileTemplate.value = remoteProxyUrl()
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
        // Cancel the predecessor, then take the mutex. The old job may still be unwinding, and two
        // writers against one archive is how a database gets corrupted mid-cancel.
        downloadJob?.cancel()
        downloadJob = scope.launch {
            downloadMutex.withLock {
                runCatching { downloadInternal(bounds.normalized(), zMin, zMax, urlTemplate) }
                    .onFailure {
                        if (it is CancellationException) throw it
                        _downloadState.value = TileDownloadState.Failed(it.message ?: "Tile download failed")
                    }
            }
        }
    }

    /**
     * Archive keyed by provider identity.
     *
     * Every provider used to write the same field.mbtiles, and the per-tile skip check keys only on
     * z/x/y. Two providers therefore interleaved into one database: a tile fetched from server A
     * made server B's identically numbered tile look already downloaded, so the archive silently
     * mixed styles and the metadata named only the last provider used.
     */
    private fun archiveFor(providerUrl: String): File {
        val key = TileIdentity.identityKey(providerUrl) ?: return File(tileDir, "field-unusable.mbtiles")
        return File(tileDir, "field-$key.mbtiles")
    }

    /**
     * Archives eligible for serving, in priority order.
     *
     * The read layer used to walk every archive and return the first hit at a given z/x/y, so two
     * providers covering the same area blended tile by tile into one image: a road style from one
     * stream alternating with a satellite style from another, with nothing on screen to say so.
     * Only the archive matching the configured provider is consulted, plus the bundled overview,
     * which is a deliberate base layer rather than somebody else's style. Other providers'
     * archives stay on disk and intact, they are simply not mixed into this one's rendering.
     */
    private fun servingArchives(): List<File> {
        val current = configuredTileUrlOrNull()?.let { TileIdentity.identityKey(it) }
        val active = current?.let { "field-$it.mbtiles" }

        // The archive for the provider currently configured, if any.
        val providerArchives = mbtilesFiles.filter { it.name == active }
        // Anything that is not an auto-downloaded provider archive is an import or a hand-placed
        // package, and stays usable by design regardless of provider settings. Excluding those
        // because no internet provider is configured would leave the default offline case showing
        // an empty map, which is the opposite of what offline is for.
        val imported = mbtilesFiles.filter { it.name != ASSET_WORLD_OVERVIEW_FILE && !isProviderArchive(it.name) }
        val overview = mbtilesFiles.filter { it.name == ASSET_WORLD_OVERVIEW_FILE }
        return providerArchives + imported + overview
    }

    /** Archives AirTAK downloaded itself, named field-<16 hex>.mbtiles by provider-stream identity. */
    private fun isProviderArchive(name: String): Boolean = PROVIDER_ARCHIVE_NAME.matches(name)

    /**
     * Tile count without materialising the list.
     *
     * The old code built the full list for every zoom level and only then compared against the
     * cap, so selecting a large area at high zoom allocated millions of coordinate objects and ran
     * out of memory before the guard could reject the request.
     */
    private fun countTiles(bounds: TileBounds, zoom: Int): Long {
        val topLeft = latLonToTile(bounds.maxLat, bounds.minLon, zoom)
        val bottomRight = latLonToTile(bounds.minLat, bounds.maxLon, zoom)
        if (bottomRight.x < topLeft.x || bottomRight.y < topLeft.y) return 0L
        return (bottomRight.x - topLeft.x + 1L) * (bottomRight.y - topLeft.y + 1L)
    }

    private suspend fun downloadInternal(bounds: TileBounds, minZoom: Int, maxZoom: Int, urlTemplate: String) {
        tileDir.mkdirs()

        // Count first, allocate second.
        val total = (minZoom..maxZoom).sumOf { countTiles(bounds, it) }
        if (total > MAP_MAX_TILES_PER_DOWNLOAD) {
            _downloadState.value = TileDownloadState.Failed(
                "Selected area is $total tiles; the limit is $MAP_MAX_TILES_PER_DOWNLOAD. " +
                    "Narrow the map or the zoom range.",
            )
            return
        }

        val file = archiveFor(urlTemplate)
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        val outcome: Outcome
        try {
            db.execSQL("CREATE TABLE IF NOT EXISTS metadata (name TEXT PRIMARY KEY, value TEXT)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS tiles (zoom_level INTEGER, tile_column INTEGER, " +
                    "tile_row INTEGER, tile_data BLOB, PRIMARY KEY (zoom_level, tile_column, tile_row))",
            )
            setMetadata(db, "name", "AirTAK Field")
            setMetadata(db, "format", "png")
            setMetadata(db, "type", "overlay")
            setMetadata(db, "bounds", "${bounds.minLon},${bounds.minLat},${bounds.maxLon},${bounds.maxLat}")
            setMetadata(db, "minzoom", minZoom.toString())
            setMetadata(db, "maxzoom", maxZoom.toString())
            // Source identity, so an archive is never an anonymous blend of servers.
            // Provenance without the credential: the label drops the query, the identity is a hash
            // of the whole normalised template, so style, port and account are distinguished in the
            // archive without writing an API key into user data.
            setMetadata(db, "attribution", TileIdentity.provenanceLabel(urlTemplate))
            setMetadata(db, "airtak_identity", TileIdentity.identityKey(urlTemplate) ?: "unknown")

            outcome = fillDatabase(db, bounds, minZoom, maxZoom, urlTemplate)
            outcome.aborted?.let { reason ->
                val message = if (reason == "no-store") {
                    "Provider sent Cache-Control: no-store. Offline persistence is not permitted by " +
                        "this provider, so the download stopped. Tiles stored so far are untouched."
                } else {
                    "Download stopped: $reason"
                }
                throw TileDownloadAborted(message)
            }
        } finally {
            // Cancellation, a network throw or a failed insert used to leave the database handle
            // open with no path to close it.
            runCatching { db.close() }
        }

        file.setLastModified(System.currentTimeMillis())
        refreshLocalTiles()

        val missing = outcome.missing
        val failed = outcome.failed
        if (missing + failed > 0) {
            // Reporting Complete here claimed an offline area that had holes in it, which is the
            // worst possible lie for a tool used offline. Stored tiles remain valid and usable.
            _downloadState.value = TileDownloadState.Failed(
                "Incomplete: $missing tiles absent, $failed failed, $outcome.stored stored and " +
                    "${outcome.skipped} already present. Partial area is usable offline.",
            )
        } else {
            _downloadState.value = TileDownloadState.Complete(file.name, outcome.stored, outcome.skipped)
        }
    }

    private data class Outcome(
        val stored: Int,
        val skipped: Int,
        val missing: Int,
        val failed: Int,
        val aborted: String?,
    ) {
        override fun toString(): String = "Outcome(stored=$stored)"
    }

    /** Adapts the policy-aware fetcher to the listener's narrow outcome contract. */
    private val remoteSource = object : RemoteTileSource {
        override suspend fun fetch(z: Int, x: Int, y: Int): RemoteTileOutcome =
            when (
                val r = remoteTiles.fetch(
                    configuredTileUrlOrNull(),
                    z,
                    x,
                    y,
                    TileProxyHandler.MAX_SUPPORTED_ZOOM,
                )
            ) {
                is RemoteTileFetcher.Result.Fetched -> RemoteTileOutcome.Found(r.bytes, r.rawCacheControl)
                is RemoteTileFetcher.Result.NotFound -> RemoteTileOutcome.Missing
                is RemoteTileFetcher.Result.Failed -> RemoteTileOutcome.Unavailable
                is RemoteTileFetcher.Result.Refused -> RemoteTileOutcome.Refused
                is RemoteTileFetcher.Result.NoProvider -> RemoteTileOutcome.Refused
            }
    }

    private suspend fun fillDatabase(
        db: SQLiteDatabase,
        bounds: TileBounds,
        minZoom: Int,
        maxZoom: Int,
        urlTemplate: String,
    ): Outcome {
        var stored = 0
        var skipped = 0
        var missing = 0
        var failed = 0
        var index = 0
        val total = (minZoom..maxZoom).sumOf { countTiles(bounds, it) }.toInt().coerceAtLeast(1)

        for (z in minZoom..maxZoom) {
            for (tile in tileRange(bounds, z)) {
                coroutineContext.ensureActive()
                _downloadState.value = TileDownloadState.Running(index, total)
                index++

                if (tile.z < 0 || tile.z > TileProxyHandler.MAX_SUPPORTED_ZOOM) { failed++; continue }
                val span = 1L shl tile.z
                if (tile.x < 0L || tile.x >= span || tile.y < 0L || tile.y >= span) { failed++; continue }

                val tmsY = ((1L shl tile.z) - 1 - tile.y).toInt()
                if (hasTile(db, tile.z, tile.x, tmsY)) { skipped++; continue }

                val url = urlTemplate
                    .replace("{z}", tile.z.toString())
                    .replace("{x}", tile.x.toString())
                    .replace("{y}", tile.y.toString())
                when (val result = remoteTiles.fetch(url, tile.z, tile.x, tile.y, TileProxyHandler.MAX_SUPPORTED_ZOOM)) {
                    is RemoteTileFetcher.Result.Fetched -> {
                        if (result.directives.persistenceProhibited) {
                            return Outcome(stored, skipped, missing, failed, "no-store")
                        }
                        val values = ContentValues().apply {
                            put("zoom_level", tile.z)
                            put("tile_column", tile.x)
                            put("tile_row", tmsY)
                            put("tile_data", result.bytes)
                        }
                        if (db.insertWithOnConflict("tiles", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L) {
                            stored++
                        } else {
                            failed++
                        }
                    }
                    is RemoteTileFetcher.Result.NotFound -> missing++
                    is RemoteTileFetcher.Result.Failed -> failed++
                    is RemoteTileFetcher.Result.Refused -> return Outcome(stored, skipped, missing, failed, result.reason)
                    is RemoteTileFetcher.Result.NoProvider -> return Outcome(stored, skipped, missing, failed, result.reason)
                }
            }
        }
        return Outcome(stored, skipped, missing, failed, null)
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
        ensureServer()
        servingFiles = servingArchives()
        _localTileTemplate.value = localTileUrl()
        _tileTemplate.value = remoteProxyUrl()
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
        return tileServer.port?.takeIf { it > 0 }?.let { port ->
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
        val port = tileServer.port.takeIf { it > 0 } ?: return null
        val provider = configuredTileUrlOrNull() ?: return null
        if (TileProviderPolicy.verdictForLiveFetch(provider) !is LiveFetchVerdict.Allowed) return null
        return "http://127.0.0.1:$port/remote/{z}/{x}/{y}.png"
    }

    private fun ensureServer() {
        runCatching { tileServer.start() }
    }

    /**
     * Close the listener and every live connection, and report what actually closed. A worker
     * blocked in a synchronous archive read cannot be preempted, so the report is the honest answer
     * rather than an assumption that everything drained.
     */
    fun shutdown(): TileServer.ShutdownReport = tileServer.shutdown()

    private fun ByteArray.contentType(): String = when {
        size >= 8 && this[0] == 0x89.toByte() && this[1] == 0x50.toByte() && this[2] == 0x4E.toByte() -> "image/png"
        size >= 3 && this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte() && this[2] == 0xFF.toByte() -> "image/jpeg"
        size >= 12 && copyOfRange(0, 4).decodeToString() == "RIFF" && copyOfRange(8, 12).decodeToString() == "WEBP" -> "image/webp"
        else -> "application/octet-stream"
    }

    private fun readTile(z: Int, x: Int, y: Int): ByteArray? {
        if (z < 0 || z > TileProxyHandler.MAX_SUPPORTED_ZOOM) return null
        val span = 1L shl z
        if (x < 0L || x >= span || y < 0L || y >= span) return null
        val tmsY = ((1L shl z) - 1 - y).toInt()
        for (file in servingFiles) {
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
        val PROVIDER_ARCHIVE_NAME = Regex("field-[0-9a-f]{16}\\.mbtiles")

        val TILE_PATH = Regex("/tiles/(\\d+)/(\\d+)/(\\d+)\\.png")
    }
}