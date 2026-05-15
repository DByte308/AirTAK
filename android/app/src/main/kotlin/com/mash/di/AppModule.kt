// MASH Android — di/AppModule.kt
// Provides Room database, DAOs, and repository singletons via Hilt.

package com.mash.di

import android.content.Context
import androidx.room.Room
import com.mash.data.db.MashDatabase
import com.mash.data.db.MessageDao
import com.mash.data.db.NodeDao
import com.mash.data.db.WaypointDao
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
    fun provideDatabase(@ApplicationContext ctx: Context): MashDatabase =
        Room.databaseBuilder(ctx, MashDatabase::class.java, "mash.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideNodeDao(db: MashDatabase): NodeDao = db.nodeDao()

    @Provides fun provideMessageDao(db: MashDatabase): MessageDao = db.messageDao()

    @Provides fun provideWaypointDao(db: MashDatabase): WaypointDao = db.waypointDao()
}
