// AirTAK Android — data/db/AirTAKDatabase.kt
// Planning: §11 | Roadmap: Phase 3
//
// Room database with 3 tables: messages (1000-message rolling window), nodes, waypoints.
// WAL mode for concurrent read/write from BLE coroutine and UI coroutine.

package com.airtak.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities   = [MessageEntity::class, NodeEntity::class, WaypointEntity::class],
    version    = 1,
    exportSchema = false,
)
abstract class AirTAKDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun nodeDao(): NodeDao
    abstract fun waypointDao(): WaypointDao
}
