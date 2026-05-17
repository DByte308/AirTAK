// AirTAK Android — data/db/TacticalMarkerEntity.kt
// Roadmap: 5.3, 5.4 | Local cache of TacticalMarker (proto) packets.
//
// Points are stored as a flat "lat,lng;lat,lng;…" string to avoid pulling a
// JSON dependency and to keep migrations trivial. Encoding/decoding helpers
// live in [TacticalMarkerEntity].

package com.airtak.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Locale

@Entity(
    tableName = "tactical_markers",
    indices = [
        Index("updatedAtUnix"),
        Index("creatorNodeId"),
    ],
)
data class TacticalMarkerEntity(
    /** Stable 32-bit identifier from the wire (creator-assigned random). */
    @PrimaryKey val markerId: Long,
    /** Proto MarkerType ordinal: 0=POINT, 1=ROUTE, 2=AREA, 3=NOTE, 4=OBJECTIVE. */
    val typeValue: Int,
    val label: String,
    val description: String,
    val colorRgb: Int,
    /** Proto Scope ordinal: 0=ALL, 1=COMPANY, 2=PLATOON, 3=SQUAD, 4=FIRE_TEAM. */
    val scopeValue: Int,
    val creatorNodeId: Long,
    val createdAtUnix: Long,
    val updatedAtUnix: Long,
    val deleted: Boolean,
    /** Polyline points as "lat,lng;lat,lng;…" (WGS-84, 6 decimal digits). */
    val pointsEncoded: String,
    /** Proto TeamControl ordinal; meaningful only for OBJECTIVE. */
    val controllingTeamValue: Int,
    /** Proto priority (0–4); meaningful only for OBJECTIVE. */
    val priority: Int,
    /** Local wall-clock receipt time, used to age out stale markers. */
    val lastSeenMs: Long,
    /** True when the marker was created on this device (drives re-broadcast). */
    val ownedLocally: Boolean,
) {
    fun points(): List<Pair<Double, Double>> = decodePoints(pointsEncoded)

    companion object {
        fun encodePoints(points: List<Pair<Double, Double>>): String =
            points.joinToString(";") { (lat, lng) -> "%.6f,%.6f".format(Locale.ROOT, lat, lng) }

        fun decodePoints(encoded: String): List<Pair<Double, Double>> {
            if (encoded.isBlank()) return emptyList()
            return encoded.split(';').mapNotNull { pair ->
                val parts = pair.split(',')
                if (parts.size != 2) return@mapNotNull null
                val lat = parts[0].toDoubleOrNull() ?: return@mapNotNull null
                val lng = parts[1].toDoubleOrNull() ?: return@mapNotNull null
                lat to lng
            }
        }
    }
}
