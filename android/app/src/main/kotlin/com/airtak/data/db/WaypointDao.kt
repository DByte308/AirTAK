// AirTAK Android — data/db/WaypointDao.kt

package com.airtak.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WaypointDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(waypoint: WaypointEntity): Long

    @Delete
    suspend fun delete(waypoint: WaypointEntity)

    @Query("SELECT * FROM waypoints ORDER BY createdMs DESC")
    fun observeAll(): Flow<List<WaypointEntity>>
}