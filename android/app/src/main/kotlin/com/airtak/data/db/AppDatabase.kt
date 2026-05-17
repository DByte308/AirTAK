// AirTAK Android — data/db/AppDatabase.kt

package com.airtak.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        MessageEntity::class,
        RemoteNodeEntity::class,
        WaypointEntity::class,
        TacticalMarkerEntity::class,
        ModerationVoteEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun remoteNodeDao(): RemoteNodeDao
    abstract fun waypointDao(): WaypointDao
    abstract fun tacticalMarkerDao(): TacticalMarkerDao
    abstract fun moderationVoteDao(): ModerationVoteDao
}
