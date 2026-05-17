// AirTAK Android — data/db/TacticalMarkerDao.kt

package com.airtak.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TacticalMarkerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(marker: TacticalMarkerEntity)

    @Query("SELECT * FROM tactical_markers WHERE deleted = 0 ORDER BY updatedAtUnix DESC")
    fun observeActive(): Flow<List<TacticalMarkerEntity>>

    @Query("SELECT * FROM tactical_markers WHERE markerId = :markerId LIMIT 1")
    suspend fun get(markerId: Long): TacticalMarkerEntity?

    @Query("SELECT * FROM tactical_markers WHERE ownedLocally = 1 AND deleted = 0 ORDER BY updatedAtUnix DESC")
    suspend fun getOwnedActive(): List<TacticalMarkerEntity>

    /** Permanently removes tombstones older than [olderThanUnix] (seconds). */
    @Query("DELETE FROM tactical_markers WHERE deleted = 1 AND updatedAtUnix < :olderThanUnix")
    suspend fun purgeTombstones(olderThanUnix: Long)

    @Query("UPDATE tactical_markers SET delivered = 1 WHERE markerId = :markerId")
    suspend fun markDelivered(markerId: Long)

    @Query("DELETE FROM tactical_markers")
    suspend fun clear()
}
