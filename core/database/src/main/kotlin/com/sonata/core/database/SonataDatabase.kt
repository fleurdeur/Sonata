package com.sonata.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.sonata.core.database.dao.ImportJobDao
import com.sonata.core.database.dao.LyricsDao
import com.sonata.core.database.dao.MixDao
import com.sonata.core.database.dao.PlaylistDao
import com.sonata.core.database.dao.PresetDao
import com.sonata.core.database.dao.SoundDao
import com.sonata.core.database.entity.ImportJobEntity
import com.sonata.core.database.entity.LyricsEntity
import com.sonata.core.database.entity.MixEntity
import com.sonata.core.database.entity.PlaylistEntity
import com.sonata.core.database.entity.PlaylistSoundCrossRef
import com.sonata.core.database.entity.PresetEntity
import com.sonata.core.database.entity.SoundEntity
import com.sonata.core.database.entity.SoundFtsEntity
import com.sonata.core.database.entity.TransitionEntity

@Database(
    version = 3,
    exportSchema = true,
    entities = [
        SoundEntity::class,
        SoundFtsEntity::class,
        PlaylistEntity::class,
        PlaylistSoundCrossRef::class,
        MixEntity::class,
        TransitionEntity::class,
        PresetEntity::class,
        ImportJobEntity::class,
        LyricsEntity::class,
    ],
)
abstract class SonataDatabase : RoomDatabase() {
    abstract fun soundDao(): SoundDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun mixDao(): MixDao
    abstract fun presetDao(): PresetDao
    abstract fun importJobDao(): ImportJobDao
    abstract fun lyricsDao(): LyricsDao

    companion object {
        const val NAME = "sonata.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS lyrics (" +
                        "soundId INTEGER NOT NULL, " +
                        "provider TEXT NOT NULL, " +
                        "syncedLrc TEXT, " +
                        "plainText TEXT, " +
                        "trackName TEXT, " +
                        "artistName TEXT, " +
                        "timeScale REAL NOT NULL DEFAULT 1.0, " +
                        "updatedAt INTEGER NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY(soundId), " +
                        "FOREIGN KEY(soundId) REFERENCES sounds(id) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_lyrics_soundId ON lyrics (soundId)")
            }
        }

        /**
         * Adds resumable import diagnostics and the per-track lyric offset.
         * Both are additive and nullable/defaulted, so upgrading keeps the
         * whole library intact.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE import_jobs ADD COLUMN failure TEXT")
                db.execSQL("ALTER TABLE import_jobs ADD COLUMN diagnostics TEXT")
                db.execSQL("ALTER TABLE import_jobs ADD COLUMN attempt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE import_jobs ADD COLUMN fullSongVerified INTEGER")
                db.execSQL("ALTER TABLE import_jobs ADD COLUMN matchConfidence REAL")
                db.execSQL("ALTER TABLE lyrics ADD COLUMN offsetMs INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
