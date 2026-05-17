// AirTAK Android — data/db/ModerationVoteEntity.kt
// Roadmap: 5.10 | Vote-kick / shadowban ledger.
//
// Each row records one ModerationPacket received (or sent by us). Three
// distinct (target, scope) VOTE_BAN votes within the TTL window activate
// a ban; any UNBAN from a previous voter clears it immediately.

package com.airtak.data.db

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "moderation_votes",
    primaryKeys = ["targetNodeId", "voterNodeId", "scopeValue", "actionValue"],
    indices = [
        Index("targetNodeId"),
        Index("timestampUnix"),
    ],
)
data class ModerationVoteEntity(
    val targetNodeId: Long,
    val voterNodeId: Long,
    /** airtak.Scope ordinal (0..4). */
    val scopeValue: Int,
    /** airtak.ModerationAction ordinal (0=VOTE_BAN, 1=UNBAN). */
    val actionValue: Int,
    /** Wall-clock timestamp from the wire (seconds since epoch). */
    val timestampUnix: Long,
    /** Local receipt time, used for aging. */
    val receivedMs: Long,
)
