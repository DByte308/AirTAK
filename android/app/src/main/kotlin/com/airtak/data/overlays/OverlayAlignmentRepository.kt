// AirTAK Android — data/overlays/OverlayAlignmentRepository.kt
//
// Receives OverlayAlignment packets from the mesh, stores them keyed by
// (sharer_node_id, image_hash), and exposes the collection so the UI can
// let the user pick an alignment to import against a locally-available image.
//
// When importing, the user selects a local image file via picker; the repo
// hashes it, finds the matching alignment, and applies the corners/opacity.

package com.airtak.data.overlays

import android.content.Context
import android.net.Uri
import com.airtak.data.mesh.MeshRepository
import com.airtak.data.mesh.MeshRxPacket
import com.airtak.config.constants.PORTNUM_AIRTAK_OVERLAY_ALIGNMENT
import com.airtak.proto.OverlayAlignment
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import com.airtak.config.constants.UI_OVERLAY_HASH_BYTES
import com.airtak.config.constants.UI_STREAM_COPY_BUFFER_BYTES
import com.airtak.config.constants.KEY_ALIGNMENTS
import com.airtak.config.constants.UI_OVERLAY_OPACITY_DEFAULT
import com.airtak.config.constants.UI_OVERLAY_OPACITY_MIN
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class ReceivedAlignment(
    val sharerNodeId: Long,
    val imageHash: Long,
    val imageFileName: String,
    val topLeftLat: Double,
    val topLeftLng: Double,
    val topRightLat: Double,
    val topRightLng: Double,
    val bottomRightLat: Double,
    val bottomRightLng: Double,
    val bottomLeftLat: Double,
    val bottomLeftLng: Double,
    val opacity: Float,
    val timestampUnix: Long,
)

@Singleton
class OverlayAlignmentRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mesh: MeshRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("airtak.overlay_alignments", Context.MODE_PRIVATE)

    private val _alignments = MutableStateFlow<List<ReceivedAlignment>>(loadAlignments())
    val alignments: StateFlow<List<ReceivedAlignment>> = _alignments.asStateFlow()

    init {
        scope.launch {
            mesh.packets
                .filter { it.portnum == PORTNUM_AIRTAK_OVERLAY_ALIGNMENT }
                .collect { handleIncoming(it) }
        }
    }

    private suspend fun handleIncoming(packet: MeshRxPacket) {
        val payload = packet.payload ?: return
        val proto = runCatching { OverlayAlignment.parseFrom(payload) }.getOrNull() ?: return
        val received = ReceivedAlignment(
            sharerNodeId = proto.sharerNodeId.toLong() and 0xFFFF_FFFFL,
            imageHash = proto.imageHash,
            imageFileName = proto.imageFileName,
            topLeftLat = proto.topLeftLat,
            topLeftLng = proto.topLeftLng,
            topRightLat = proto.topRightLat,
            topRightLng = proto.topRightLng,
            bottomRightLat = proto.bottomRightLat,
            bottomRightLng = proto.bottomRightLng,
            bottomLeftLat = proto.bottomLeftLat,
            bottomLeftLng = proto.bottomLeftLng,
            opacity = proto.opacity.coerceIn(UI_OVERLAY_OPACITY_MIN, 1f),
            timestampUnix = proto.timestampUnix.toLong() and 0xFFFF_FFFFL,
        )
        upsertAlignment(received)
    }

    private fun upsertAlignment(alignment: ReceivedAlignment) {
        val current = _alignments.value.toMutableList()
        val idx = current.indexOfFirst {
            it.sharerNodeId == alignment.sharerNodeId && it.imageHash == alignment.imageHash
        }
        if (idx >= 0) current[idx] = alignment else current.add(alignment)
        _alignments.value = current
        persistAll(current)
    }

    /** Compute the 8-byte hash of a local image file to match against received alignments. */
    suspend fun computeImageHash(uri: Uri): Long = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use { input ->
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
        hash
    }

    /** Returns matching ReceivedAlignment for a given image hash, or null. */
    fun findAlignmentForHash(imageHash: Long): ReceivedAlignment? =
        _alignments.value.firstOrNull { it.imageHash == imageHash }

    /** Returns alignments from a specific sharer, latest first. */
    fun alignmentsFromSharer(sharerNodeId: Long): List<ReceivedAlignment> =
        _alignments.value.filter { it.sharerNodeId == sharerNodeId }
            .sortedByDescending { it.timestampUnix }

    private fun persistAll(list: List<ReceivedAlignment>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_ALIGNMENTS, arr.toString()).apply()
    }

    private fun loadAlignments(): List<ReceivedAlignment> {
        val raw = prefs.getString(KEY_ALIGNMENTS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i -> arr.getJSONObject(i).toAlignment() }
        }.getOrDefault(emptyList())
    }

    private fun ReceivedAlignment.toJson(): JSONObject = JSONObject()
        .put("sharerNodeId", sharerNodeId)
        .put("imageHash", imageHash)
        .put("imageFileName", imageFileName)
        .put("topLeftLat", topLeftLat)
        .put("topLeftLng", topLeftLng)
        .put("topRightLat", topRightLat)
        .put("topRightLng", topRightLng)
        .put("bottomRightLat", bottomRightLat)
        .put("bottomRightLng", bottomRightLng)
        .put("bottomLeftLat", bottomLeftLat)
        .put("bottomLeftLng", bottomLeftLng)
        .put("opacity", opacity.toDouble())
        .put("timestampUnix", timestampUnix)

    private fun JSONObject.toAlignment(): ReceivedAlignment = ReceivedAlignment(
        sharerNodeId = getLong("sharerNodeId"),
        imageHash = getLong("imageHash"),
        imageFileName = optString("imageFileName", ""),
        topLeftLat = getDouble("topLeftLat"),
        topLeftLng = getDouble("topLeftLng"),
        topRightLat = getDouble("topRightLat"),
        topRightLng = getDouble("topRightLng"),
        bottomRightLat = getDouble("bottomRightLat"),
        bottomRightLng = getDouble("bottomRightLng"),
        bottomLeftLat = getDouble("bottomLeftLat"),
        bottomLeftLng = getDouble("bottomLeftLng"),
        opacity = optDouble("opacity", UI_OVERLAY_OPACITY_DEFAULT.toDouble()).toFloat(),
        timestampUnix = getLong("timestampUnix"),
    )

    private companion object {
    }
}
