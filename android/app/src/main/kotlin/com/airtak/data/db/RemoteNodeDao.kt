// AirTAK Android — data/db/RemoteNodeDao.kt

package com.airtak.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RemoteNodeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(node: RemoteNodeEntity)

    @Query("SELECT * FROM remote_nodes WHERE nodeId = :nodeId LIMIT 1")
    suspend fun get(nodeId: Long): RemoteNodeEntity?

    @Query("SELECT * FROM remote_nodes ORDER BY companyId ASC, platoonId ASC, squadId ASC, fireteamId ASC, callsign COLLATE NOCASE ASC, shortName COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<RemoteNodeEntity>>

    @Query("DELETE FROM remote_nodes WHERE lastSeenMs < :olderThanMs")
    suspend fun deleteOlderThan(olderThanMs: Long)
}