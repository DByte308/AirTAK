// AirTAK Android — data/db/MessageDao.kt

package com.airtak.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE channelIndex = :channel ORDER BY timestampMs DESC LIMIT :limit")
    fun observe(channel: Int, limit: Int = 500): Flow<List<MessageEntity>>

    @Query("SELECT COUNT(*) FROM messages WHERE channelIndex = :channel")
    suspend fun count(channel: Int): Int

    /** Prune oldest rows when the rolling window is exceeded. */
    @Query("""
        DELETE FROM messages
        WHERE channelIndex = :channel
          AND id NOT IN (
              SELECT id FROM messages
              WHERE channelIndex = :channel
              ORDER BY timestampMs DESC
              LIMIT :keep
          )
    """)
    suspend fun pruneOldest(channel: Int, keep: Int)

    @Query("DELETE FROM messages WHERE channelIndex = :channel")
    suspend fun clear(channel: Int)
}
