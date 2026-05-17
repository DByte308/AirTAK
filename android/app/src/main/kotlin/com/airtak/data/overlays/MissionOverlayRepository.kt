// AirTAK Android - data/overlays/MissionOverlayRepository.kt

package com.airtak.data.overlays

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class MissionOverlayPoint(
    val latitude: Double,
    val longitude: Double,
)

data class MissionOverlayCorners(
    val topLeft: MissionOverlayPoint,
    val topRight: MissionOverlayPoint,
    val bottomRight: MissionOverlayPoint,
    val bottomLeft: MissionOverlayPoint,
)

enum class MissionOverlayCornerId {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_RIGHT,
    BOTTOM_LEFT,
}

data class MissionOverlay(
    val imagePath: String,
    val corners: MissionOverlayCorners,
    val opacity: Float = DEFAULT_MISSION_OVERLAY_OPACITY,
    val updatedMs: Long,
)

@Singleton
class MissionOverlayRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences("airtak.mission_overlay", Context.MODE_PRIVATE)
    private val overlayDir = File(context.filesDir, "overlays")
    private val overlayFile = File(overlayDir, "mission-overlay.image")
    private val legacyOverlayFile = File(overlayDir, "mission-overlay.png")

    private val _overlay = MutableStateFlow(loadOverlay())
    val overlay: StateFlow<MissionOverlay?> = _overlay.asStateFlow()

    suspend fun importImage(uri: Uri, corners: MissionOverlayCorners) = withContext(Dispatchers.IO) {
        overlayDir.mkdirs()
        val temp = File(overlayDir, "mission-overlay.tmp")
        context.contentResolver.openInputStream(uri)?.use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Could not open mission overlay image")
        if (temp.length() <= 0L) {
            temp.delete()
            error("Mission overlay image is empty")
        }
        deleteCurrentOverlayImage()
        check(temp.renameTo(overlayFile)) { "Could not save mission overlay image" }
        val overlay = MissionOverlay(
            imagePath = overlayFile.absolutePath,
            corners = corners,
            opacity = DEFAULT_MISSION_OVERLAY_OPACITY,
            updatedMs = System.currentTimeMillis(),
        )
        saveOverlay(overlay)
        _overlay.value = overlay
    }

    fun updateCorner(cornerId: MissionOverlayCornerId, point: MissionOverlayPoint) {
        val current = _overlay.value ?: return
        val updated = current.copy(
            corners = current.corners.withCorner(cornerId, point),
            updatedMs = System.currentTimeMillis(),
        )
        saveOverlay(updated)
        _overlay.value = updated
    }

    fun updateOpacity(opacity: Float) {
        val current = _overlay.value ?: return
        val updated = current.copy(
            opacity = opacity.coerceIn(MIN_MISSION_OVERLAY_OPACITY, 1f),
            updatedMs = System.currentTimeMillis(),
        )
        saveOverlay(updated)
        _overlay.value = updated
    }

    fun remove() {
        deleteCurrentOverlayImage()
        prefs.edit().remove(KEY_OVERLAY).apply()
        _overlay.value = null
    }

    private fun deleteCurrentOverlayImage() {
        _overlay.value?.imagePath?.let { File(it).delete() }
        overlayFile.delete()
        legacyOverlayFile.delete()
    }

    private fun MissionOverlayCorners.withCorner(
        cornerId: MissionOverlayCornerId,
        point: MissionOverlayPoint,
    ): MissionOverlayCorners = when (cornerId) {
        MissionOverlayCornerId.TOP_LEFT -> copy(topLeft = point)
        MissionOverlayCornerId.TOP_RIGHT -> copy(topRight = point)
        MissionOverlayCornerId.BOTTOM_RIGHT -> copy(bottomRight = point)
        MissionOverlayCornerId.BOTTOM_LEFT -> copy(bottomLeft = point)
    }

    private fun saveOverlay(overlay: MissionOverlay) {
        val json = JSONObject()
            .put("imagePath", overlay.imagePath)
            .put("updatedMs", overlay.updatedMs)
            .put("opacity", overlay.opacity.toDouble())
            .put("topLeft", overlay.corners.topLeft.toJson())
            .put("topRight", overlay.corners.topRight.toJson())
            .put("bottomRight", overlay.corners.bottomRight.toJson())
            .put("bottomLeft", overlay.corners.bottomLeft.toJson())
        prefs.edit().putString(KEY_OVERLAY, json.toString()).apply()
    }

    private fun loadOverlay(): MissionOverlay? {
        val raw = prefs.getString(KEY_OVERLAY, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val imagePath = json.getString("imagePath")
            if (!File(imagePath).exists()) return null
            MissionOverlay(
                imagePath = imagePath,
                updatedMs = json.optLong("updatedMs", 0L),
                opacity = json.optDouble("opacity", DEFAULT_MISSION_OVERLAY_OPACITY.toDouble())
                    .toFloat()
                    .coerceIn(MIN_MISSION_OVERLAY_OPACITY, 1f),
                corners = MissionOverlayCorners(
                    topLeft = json.getJSONObject("topLeft").toPoint(),
                    topRight = json.getJSONObject("topRight").toPoint(),
                    bottomRight = json.getJSONObject("bottomRight").toPoint(),
                    bottomLeft = json.getJSONObject("bottomLeft").toPoint(),
                ),
            )
        }.getOrNull()
    }

    private fun MissionOverlayPoint.toJson(): JSONObject = JSONObject()
        .put("latitude", latitude)
        .put("longitude", longitude)

    private fun JSONObject.toPoint(): MissionOverlayPoint = MissionOverlayPoint(
        latitude = getDouble("latitude"),
        longitude = getDouble("longitude"),
    )

    private companion object {
        const val KEY_OVERLAY = "overlay"
    }
}

const val DEFAULT_MISSION_OVERLAY_OPACITY = 0.72f
const val MIN_MISSION_OVERLAY_OPACITY = 0.1f
