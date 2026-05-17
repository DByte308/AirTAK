// AirTAK Android — data/db/ModerationVoteDao.kt

package com.airtak.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ModerationVoteDao {
    /** REPLACE so a later vote with the same primary key supersedes earlier ones. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(vote: ModerationVoteEntity)

    @Query("SELECT * FROM moderation_votes")
    fun observeAll(): Flow<List<ModerationVoteEntity>>

    @Query("DELETE FROM moderation_votes WHERE timestampUnix < :olderThanUnix")
    suspend fun pruneOlderThan(olderThanUnix: Long)

    @Query("DELETE FROM moderation_votes WHERE targetNodeId = :targetNodeId AND scopeValue = :scopeValue")
    suspend fun clearForTargetScope(targetNodeId: Long, scopeValue: Int)
}
