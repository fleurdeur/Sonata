package com.sonata.core.database.di

import android.content.Context
import androidx.room.Room
import com.sonata.core.database.SonataDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SonataDatabase =
        Room.databaseBuilder(context, SonataDatabase::class.java, SonataDatabase.NAME)
            .addMigrations(SonataDatabase.MIGRATION_1_2, SonataDatabase.MIGRATION_2_3)
            // No destructive fallback: a missing migration must never wipe a library.
            .build()

    @Provides fun soundDao(db: SonataDatabase) = db.soundDao()
    @Provides fun playlistDao(db: SonataDatabase) = db.playlistDao()
    @Provides fun mixDao(db: SonataDatabase) = db.mixDao()
    @Provides fun presetDao(db: SonataDatabase) = db.presetDao()
    @Provides fun importJobDao(db: SonataDatabase) = db.importJobDao()
    @Provides fun lyricsDao(db: SonataDatabase) = db.lyricsDao()
}