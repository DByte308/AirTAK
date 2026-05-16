// AirTAK Android — data/db/WaypointEntity.kt

package com.airtak.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "waypoints",
    indices = [Index("createdMs")],
)
data class WaypointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val icon: String = "flag",
    val latitude: Double,
    val longitude: Double,
    val createdMs: Long,
)