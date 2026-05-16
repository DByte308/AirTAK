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

    @Query("UPDATE messages SET delivered = 1 WHERE id = :id")
    suspend fun markDelivered(id: Long)

    /**
     * Observe DM conversation with a specific peer (outgoing peerNodeId = peer,
     * incoming sourceNodeId = peer). Newest first so LazyColumn reverseLayout works.
     */
    @Query("""
        SELECT * FROM messages
        WHERE peerNodeId = :peerNodeId AND peerNodeId != 0
        ORDER BY timestampMs DESC
        LIMIT :limit
    """)
    fun observeDm(peerNodeId: Long, limit: Int = 500): Flow<List<MessageEntity>>

    /**
     * Returns one representative row per DM peer, ordered by most-recent message.
     * Used to build the DM peer list (inbox).
     */
    @Query("""
        SELECT * FROM messages
        WHERE peerNodeId != 0
        GROUP BY peerNodeId
        HAVING MAX(timestampMs)
        ORDER BY MAX(timestampMs) DESC
    """)
    fun observeDmPeers(): Flow<List<MessageEntity>>
}
