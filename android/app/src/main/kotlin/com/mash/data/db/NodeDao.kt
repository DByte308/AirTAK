// MASH Android — data/db/NodeDao.kt
// Planning: §13.2, §11 | Roadmap: 2.2, 3.3
//
// Stores known mesh nodes with position, status, and team information.
// Updated on every NODEINFO_APP and MASH_PLAYER packet.

package com.mash.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "nodes", primaryKeys = ["nodeId"])
data class NodeEntity(
    val nodeId: Long,
    val callsign: String,
    val teamIndex: Int,
    val squadIndex: Int,
    val latE7: Int?,
    val lonE7: Int?,
    val statusOrdinal: Int,
    val respawnSecondsRemaining: Int,
    val rssi: Float,
    val snr: Float,
    val hwModelOrdinal: Int,
    val lastSeenMs: Long,
)

@Dao
interface NodeDao {
    @Query("SELECT * FROM nodes ORDER BY callsign ASC")
    fun observeAll(): Flow<List<NodeEntity>>

    @Query("SELECT * FROM nodes WHERE nodeId = :id")
    suspend fun findById(id: Long): NodeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(node: NodeEntity)

    @Query("DELETE FROM nodes WHERE lastSeenMs < :cutoffMs")
    suspend fun evictStale(cutoffMs: Long)
}
