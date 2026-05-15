// AirTAK Android — data/db/MessageDao.kt
// Planning: §11.6 | Roadmap: 3.2
//
// Rolling 1000-message window per channel (§11.6).
// Oldest messages are deleted when the per-channel count exceeds 1000.

package com.airtak.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channelIndex: Int,
    val fromNodeId: Long,
    val fromCallsign: String,
    val text: String,
    val timestampMs: Long,
)

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE channelIndex = :channel ORDER BY timestampMs DESC LIMIT 1000")
    fun observeByChannel(channel: Int): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    // Delete oldest messages beyond 1000 for the given channel
    @Query("""
        DELETE FROM messages
        WHERE channelIndex = :channel
          AND id NOT IN (
            SELECT id FROM messages
            WHERE channelIndex = :channel
            ORDER BY timestampMs DESC
            LIMIT 1000
          )
    """)
    suspend fun pruneChannel(channel: Int)
}
