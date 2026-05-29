// AirTAK Android - data/overlays/MissionOverlayRepository.kt

package com.airtak.data.overlays

import android.content.Context
import android.net.Uri
import com.airtak.data.mesh.MeshRepository
import com.airtak.proto.OverlayAlignment
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import com.airtak.config.constants.MS_PER_SECOND
import com.airtak.config.constants.UI_OVERLAY_HASH_BYTES
import com.airtak.config.constants.UI_OVERLAY_OPACITY_DEFAULT
import com.airtak.config.constants.UI_OVERLAY_OPACITY_MIN
import com.airtak.config.constants.UI_STREAM_COPY_BUFFER_BYTES
import com.airtak.config.constants.KEY_OVERLAY
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
    val     opacity: Float = UI_OVERLAY_OPACITY_DEFAULT,
    val updatedMs: Long,
)

@Singleton
class MissionOverlayRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mesh: MeshRepository,
) {
    private val prefs = context.getSharedPreferences("airtak.mission_overlay", Context.MODE_PRIVATE)
    private val overlayDir = File(context.filesDir, "overlays")
    private val overlayFile = File(overlayDir, "mission-overlay.image")
    private val legacyOverlayFile = File(overlayDir, "mission-overlay.png")

    private val _overlay = MutableStateFlow(loadOverlay())
    val overlay: StateFlow<MissionOverlay?> = _overlay.asStateFlow()

    /** Whether the user has opted to share their overlay alignment via mesh. */
    private val _shareEnabled = MutableStateFlow(false)
    val shareEnabled: StateFlow<Boolean> = _shareEnabled.asStateFlow()

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
            opacity = UI_OVERLAY_OPACITY_DEFAULT,
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
            opacity = opacity.coerceIn(UI_OVERLAY_OPACITY_MIN, 1f),
            updatedMs = System.currentTimeMillis(),
        )
        saveOverlay(updated)
        _overlay.value = updated
    }

    fun remove() {
        deleteCurrentOverlayImage()
        prefs.edit().remove(KEY_OVERLAY).apply()
        _overlay.value = null
        _shareEnabled.value = false
    }

    fun setShareEnabled(enabled: Boolean) {
        _shareEnabled.value = enabled
        if (enabled) broadcastAlignment()
    }

    fun broadcastAlignment() {
        val overlay = _overlay.value ?: return
        val imageFile = File(overlay.imagePath)
        if (!imageFile.exists()) return
        val imageHash = computeFileHash(imageFile)
        val fileName = imageFile.name
        val nowUnix = (System.currentTimeMillis() / MS_PER_SECOND).toInt()
        val alignment = OverlayAlignment.newBuilder()
            .setSharerNodeId(0) // populated by firmware
            .setImageHash(imageHash)
            .setImageFileName(fileName)
            .setTopLeftLat(overlay.corners.topLeft.latitude)
            .setTopLeftLng(overlay.corners.topLeft.longitude)
            .setTopRightLat(overlay.corners.topRight.latitude)
            .setTopRightLng(overlay.corners.topRight.longitude)
            .setBottomRightLat(overlay.corners.bottomRight.latitude)
            .setBottomRightLng(overlay.corners.bottomRight.longitude)
            .setBottomLeftLat(overlay.corners.bottomLeft.latitude)
            .setBottomLeftLng(overlay.corners.bottomLeft.longitude)
            .setOpacity(overlay.opacity)
            .setTimestampUnix(nowUnix)
            .build()
        mesh.sendOverlayAlignment(alignment)
    }

    suspend fun importFromAlignment(
        alignment: com.airtak.data.overlays.ReceivedAlignment,
        imageUri: Uri,
    ) = withContext(Dispatchers.IO) {
        overlayDir.mkdirs()
        val temp = File(overlayDir, "mission-overlay.tmp")
        context.contentResolver.openInputStream(imageUri)?.use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Could not open image for alignment import")
        if (temp.length() <= 0L) {
            temp.delete()
            error("Image is empty")
        }
        val actualHash = computeFileHash(temp)
        if (actualHash != alignment.imageHash) {
            temp.delete()
            error("Image does not match the shared alignment. Expected file: ${alignment.imageFileName}")
        }
        deleteCurrentOverlayImage()
        check(temp.renameTo(overlayFile)) { "Could not save overlay image" }
        val newOverlay = MissionOverlay(
            imagePath = overlayFile.absolutePath,
            corners = MissionOverlayCorners(
                topLeft = MissionOverlayPoint(alignment.topLeftLat, alignment.topLeftLng),
                topRight = MissionOverlayPoint(alignment.topRightLat, alignment.topRightLng),
                bottomRight = MissionOverlayPoint(alignment.bottomRightLat, alignment.bottomRightLng),
                bottomLeft = MissionOverlayPoint(alignment.bottomLeftLat, alignment.bottomLeftLng),
            ),
            opacity = alignment.opacity,
            updatedMs = System.currentTimeMillis(),
        )
        saveOverlay(newOverlay)
        _overlay.value = newOverlay
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
                opacity = json.optDouble("opacity", UI_OVERLAY_OPACITY_DEFAULT.toDouble())
                    .toFloat()
                    .coerceIn(UI_OVERLAY_OPACITY_MIN, 1f),
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

    private fun computeFileHash(file: File): Long {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(UI_STREAM_COPY_BUFFER_BYTES)
            var read: Int
            while (input.read(buf).also { read = it } != -1) {
                digest.update(buf, 0, read)
            }
        }
        val full = digest.digest()
        var hash = 0L
        for (i in 0 until UI_OVERLAY_HASH_BYTES) {
            hash = (hash shl 8) or (full[i].toLong() and 0xFF)
        }
        return hash
    }

}


