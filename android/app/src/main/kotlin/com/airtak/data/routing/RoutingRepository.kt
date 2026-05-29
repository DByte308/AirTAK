// AirTAK Android — data/routing/RoutingRepository.kt

package com.airtak.data.routing

import android.content.Context
import android.util.Log
import com.airtak.data.tiles.TileBounds
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import com.airtak.config.constants.NETWORK_BROUTER_SEGMENTS_URL
import com.airtak.config.constants.ROUTING_GRID_SIZE_DEG
import kotlin.math.sqrt

data class RoutePoint(val latitude: Double, val longitude: Double)

sealed interface RouteCalcState {
    data object Idle : RouteCalcState
    data object Calculating : RouteCalcState
    data class Complete(val points: List<RoutePoint>, val distanceM: Double) : RouteCalcState
    data class Failed(val message: String) : RouteCalcState
}

sealed interface SegmentDownloadState {
    data object Idle : SegmentDownloadState
    data class Running(val completed: Int, val total: Int) : SegmentDownloadState
    data class Complete(val downloaded: Int, val skipped: Int) : SegmentDownloadState
    data class Failed(val message: String) : SegmentDownloadState
}

data class SegmentInfo(val name: String, val sizeBytes: Long)


@Singleton
class RoutingRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = HttpClient(Android)
    private val routingDir = File(context.filesDir, "routing")
    private val segmentDir = File(routingDir, "segments4")
    private val profileDir = File(routingDir, "profiles2")

    private val _routeState = MutableStateFlow<RouteCalcState>(RouteCalcState.Idle)
    val routeState: StateFlow<RouteCalcState> = _routeState.asStateFlow()

    private val _downloadState = MutableStateFlow<SegmentDownloadState>(SegmentDownloadState.Idle)
    val downloadState: StateFlow<SegmentDownloadState> = _downloadState.asStateFlow()

    private val _availableSegments = MutableStateFlow<List<SegmentInfo>>(emptyList())
    val availableSegments: StateFlow<List<SegmentInfo>> = _availableSegments.asStateFlow()

    private val _isDataReady = MutableStateFlow(false)
    val isDataReady: StateFlow<Boolean> = _isDataReady.asStateFlow()

    private val _selectedProfile = MutableStateFlow("trekking")
    val selectedProfile: StateFlow<String> = _selectedProfile.asStateFlow()

    val availableProfiles: List<String>
        get() = profileDir.listFiles { f -> f.name.endsWith(".brf") }
            ?.map { it.nameWithoutExtension }
            ?.filter { it != "all" }
            ?.sorted()
            ?: listOf("trekking", "hiking-mountain", "fastbike")

    init {
        routingDir.mkdirs()
        segmentDir.mkdirs()
        profileDir.mkdirs()
        extractBundledProfiles()
        refreshSegments()
    }

    private fun extractBundledProfiles() {
        if (profileDir.listFiles()?.isNotEmpty() == true) return
        runCatching {
            context.assets.list("routing/profiles2")?.forEach { name ->
                val target = File(profileDir, name)
                context.assets.open("routing/profiles2/$name").use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    private fun refreshSegments() {
        val files = segmentDir.listFiles { f -> f.name.endsWith(".rd5") } ?: emptyArray()
        _availableSegments.value = files.map { SegmentInfo(it.name, it.length()) }.sortedBy { it.name }
        _isDataReady.value = files.isNotEmpty()
    }

    fun setProfile(name: String) {
        _selectedProfile.value = name
    }

    fun calculateRoute(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        profile: String = _selectedProfile.value,
    ) {
        _routeState.value = RouteCalcState.Calculating
        scope.launch {
            runCatching {
                val (points, brouterError) = withContext(Dispatchers.Default) {
                    computeBrouterRoute(fromLat, fromLon, toLat, toLon, profile)
                }
                if (points.isEmpty()) {
                    _routeState.value = RouteCalcState.Failed(
                        brouterError ?: if (_isDataReady.value) "No route found between points" else "No routing data downloaded"
                    )
                } else {
                    val distance = calculatePathDistance(points)
                    _routeState.value = RouteCalcState.Complete(points, distance)
                }
            }.onFailure { e ->
                _routeState.value = RouteCalcState.Failed(e.message ?: "Routing failed")
            }
        }
    }

    fun clearRoute() {
        _routeState.value = RouteCalcState.Idle
    }

    fun beelineRoute(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double) {
        val points = listOf(
            RoutePoint(fromLat, fromLon),
            RoutePoint(toLat, toLon),
        )
        val distance = calculatePathDistance(points)
        _routeState.value = RouteCalcState.Complete(points, distance)
    }

    private fun computeBrouterRoute(
        fromLat: Double, fromLon: Double,
        toLat: Double, toLon: Double,
        profileName: String,
    ): Pair<List<RoutePoint>, String?> {
        if (!_isDataReady.value) return emptyList<RoutePoint>() to null

        val result = com.airtak.routing.BrouterEngine.calculateRoute(
            segmentDir, profileDir, profileName,
            fromLat, fromLon, toLat, toLon, 30_000L,
        )
        if (!result.isSuccess) {
            Log.w("RoutingRepo", "BRouter error: ${result.error}")
            return emptyList<RoutePoint>() to result.error
        }
        val coords = result.points ?: return emptyList<RoutePoint>() to "null points array"
        return coords.map { RoutePoint(it[0], it[1]) } to null
    }

    fun downloadSegments(bounds: TileBounds) {
        val minLat = bounds.minLat.toInt()
        val maxLat = bounds.maxLat.toInt()
        val minLon = bounds.minLon.toInt()
        val maxLon = bounds.maxLon.toInt()

        val names = mutableListOf<String>()
        var lat = minLat - (minLat % ROUTING_GRID_SIZE_DEG)
        while (lat <= maxLat) {
            var lon = minLon - (minLon % ROUTING_GRID_SIZE_DEG)
            while (lon <= maxLon) {
                names.add(segmentFileName(lon, lat))
                lon += ROUTING_GRID_SIZE_DEG
            }
            lat += ROUTING_GRID_SIZE_DEG
        }

        _downloadState.value = SegmentDownloadState.Running(0, names.size)
        var completed = 0
        var downloaded = 0
        var skipped = 0
        var errors = 0

        scope.launch {
            for (name in names) {
                runCatching {
                    val target = File(segmentDir, name)
                    if (target.exists()) {
                        skipped++
                    } else {
                        val url = "$NETWORK_BROUTER_SEGMENTS_URL$name"
                        val tempFile = File(segmentDir, "$name.tmp")
                        client.get(url).bodyAsChannel().toInputStream().use { input ->
                            tempFile.outputStream().use { output -> input.copyTo(output) }
                        }
                        if (tempFile.length() > 100) {
                            target.delete()
                            tempFile.renameTo(target)
                            downloaded++
                        } else {
                            tempFile.delete()
                            errors++
                            Log.w("RoutingRepo", "Segment $name downloaded ${tempFile.length()} bytes — skipping")
                        }
                    }
                }.onFailure { e ->
                    errors++
                    Log.e("RoutingRepo", "Failed to download $name: ${e.message}")
                }
                completed++
                _downloadState.value = SegmentDownloadState.Running(completed, names.size)
            }
            refreshSegments()
            if (downloaded == 0 && errors > 0) {
                _downloadState.value = SegmentDownloadState.Failed(
                    "Downloaded 0 of ${names.size} segments — check network"
                )
            } else {
                _downloadState.value = SegmentDownloadState.Complete(downloaded, skipped)
            }
        }
    }

    fun cancelDownload() {
        _downloadState.value = SegmentDownloadState.Idle
    }

    private fun segmentFileName(lon: Int, lat: Int): String {
        val ew = if (lon >= 0) "E" else "W"
        val ns = if (lat >= 0) "N" else "S"
        return "${ew}${abs(lon)}_${ns}${abs(lat)}.rd5"
    }

    companion object {
        fun calculatePathDistance(points: List<RoutePoint>): Double {
            if (points.size < 2) return 0.0
            var dist = 0.0
            for (i in 0 until points.size - 1) {
                dist += haversineDistance(
                    points[i].latitude, points[i].longitude,
                    points[i + 1].latitude, points[i + 1].longitude,
                )
            }
            return dist
        }

        fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val R = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = kotlin.math.sin(dLat / 2).pow(2) +
                kotlin.math.cos(Math.toRadians(lat1)) *
                kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2).pow(2)
            val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
            return R * c
        }
    }
}
