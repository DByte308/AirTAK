// AirTAK Android — data/db/WaypointDao.kt
// Planning: §11.4 | Roadmap: 3.5
//
// Local waypoints created by the user on the map (long-press).
// NEVER broadcast over the mesh — local-only persistent storage.

package com.airtak.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "waypoints")
data class WaypointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val latE7: Int,
    val lonE7: Int,
    val createdMs: Long,
    val color: Int,   // ARGB color for the map pin
)

@Dao
interface WaypointDao {
    @Query("SELECT * FROM waypoints ORDER BY createdMs DESC")
    fun observeAll(): Flow<List<WaypointEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(waypoint: WaypointEntity): Long

    @Delete
    suspend fun delete(waypoint: WaypointEntity)

    @Query("DELETE FROM waypoints")
    suspend fun deleteAll()
}
