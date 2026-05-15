// AirTAK Android — di/AppModule.kt
// Provides Room database, DAOs, and repository singletons via Hilt.

package com.airtak.di

import android.content.Context
import androidx.room.Room
import com.airtak.data.db.AirTAKDatabase
import com.airtak.data.db.MessageDao
import com.airtak.data.db.NodeDao
import com.airtak.data.db.WaypointDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): AirTAKDatabase =
        Room.databaseBuilder(ctx, AirTAKDatabase::class.java, "airtak.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideNodeDao(db: AirTAKDatabase): NodeDao = db.nodeDao()

    @Provides fun provideMessageDao(db: AirTAKDatabase): MessageDao = db.messageDao()

    @Provides fun provideWaypointDao(db: AirTAKDatabase): WaypointDao = db.waypointDao()
}
