// AirTAK Android — data/db/MessageEntity.kt
// Planning: §11.6 (Messages screen), §3.5 (Roadmap)
//
// Rolling 1000-message window per channel is enforced in MessageRepository.

package com.airtak.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    indices = [Index("channelIndex", "timestampMs")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Channel slot index (0 = Mesh / channel 0, 1 = Game, etc.). */
    val channelIndex: Int,
    /** Source node ID (0 = self / outgoing). */
    val sourceNodeId: Long,
    /** Sender short callsign at time of receipt; "" if unknown. */
    val sourceCallsign: String,
    /** UTF-8 message text. */
    val text: String,
    /** Wall-clock receipt time in millis since epoch. */
    val timestampMs: Long,
    /** True if originated locally (outbound). */
    val outgoing: Boolean,
    /** True after the firmware confirmed transmission (best-effort; reserved for future ACK wiring). */
    val delivered: Boolean = false,
)
