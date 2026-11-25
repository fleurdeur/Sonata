package com.sonata.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.sonata.core.database.dao.ImportJobDao
import com.sonata.core.database.dao.MixDao
import com.sonata.core.database.dao.PlaylistDao
import com.sonata.core.database.dao.PresetDao
import com.sonata.core.database.dao.SoundDao
import com.sonata.core.database.entity.ImportJobEntity
import com.sonata.core.database.entity.MixEntity
import com.sonata.core.database.entity.PlaylistEntity
import com.sonata.core.database.entity.PlaylistSoundCrossRef
import com.sonata.core.database.entity.PresetEntity
import com.sonata.core.database.entity.SoundEntity
import com.sonata.core.database.entity.SoundFtsEntity
import com.sonata.core.database.entity.TransitionEntity

@Database(
    version = 1,
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
    ],
)
abstract class SonataDatabase : RoomDatabase() {
    abstract fun soundDao(): SoundDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun mixDao(): MixDao
    abstract fun presetDao(): PresetDao
    abstract fun importJobDao(): ImportJobDao

    companion object {
        const val NAME = "sonata.db"
    }
}
