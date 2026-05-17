// AirTAK Android — data/db/RemoteNodeEntity.kt

package com.airtak.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "remote_nodes",
    indices = [
        Index("lastSeenMs"),
        Index("lastPositionMs"),
        Index("lastPlayerMs"),
    ],
)
data class RemoteNodeEntity(
    @PrimaryKey val nodeId: Long,
    val callsign: String = "",
    val shortName: String = "",
    val longName: String = "",
    val fireteamId: String = "",
    val squadId: String = "",
    val platoonId: String = "",
    val companyId: String = "",
    val statusValue: Int = PLAYER_STATUS_UNKNOWN,
    val colorRgb: Int = 0,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitudeM: Int? = null,
    val precisionBits: Int? = null,
    val positionTimeUnix: Long? = null,
    val batteryPercent: Int? = null,
    val lastSeenMs: Long,
    val lastPositionMs: Long? = null,
    val lastPlayerMs: Long? = null,
    val lastNodeInfoMs: Long? = null,
) {
    companion object {
        const val PLAYER_STATUS_UNKNOWN = -1
    }
}