// AirTAK Android — data/db/AppDatabase.kt

package com.airtak.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [MessageEntity::class, RemoteNodeEntity::class, WaypointEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun remoteNodeDao(): RemoteNodeDao
    abstract fun waypointDao(): WaypointDao
}
