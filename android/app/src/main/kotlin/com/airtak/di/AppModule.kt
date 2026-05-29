// AirTAK Android — di/AppModule.kt

package com.airtak.di

import android.content.Context
import androidx.room.Room
import com.airtak.config.constants.APP_DB_NAME
import com.airtak.data.db.AppDatabase
import com.airtak.data.db.MessageDao
import com.airtak.data.db.ModerationVoteDao
import com.airtak.data.db.RemoteNodeDao
import com.airtak.data.db.TacticalMarkerDao
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

    @Provides @Singleton
    fun provideDb(@ApplicationContext ctx: Context): AppDatabase =
        Room.databaseBuilder(ctx, AppDatabase::class.java, APP_DB_NAME)
            // Recover from schema/identity-hash mismatches (only relevant
            // during pre-1.0 development; once Phase 6 ships we'll switch to
            // explicit migrations).
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun provideMessageDao(db: AppDatabase): MessageDao = db.messageDao()

    @Provides
    fun provideRemoteNodeDao(db: AppDatabase): RemoteNodeDao = db.remoteNodeDao()

    @Provides
    fun provideWaypointDao(db: AppDatabase): WaypointDao = db.waypointDao()

    @Provides
    fun provideTacticalMarkerDao(db: AppDatabase): TacticalMarkerDao = db.tacticalMarkerDao()

    @Provides
    fun provideModerationVoteDao(db: AppDatabase): ModerationVoteDao = db.moderationVoteDao()
}
