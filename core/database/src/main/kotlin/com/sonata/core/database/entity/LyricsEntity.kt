package com.sonata.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Timed lyrics cached next to the sound. Kept in its own table so normal
 * library queries never carry a multi-kilobyte LRC string.
 */
@Entity(
    tableName = "lyrics",
    foreignKeys = [
        ForeignKey(
            entity = SoundEntity::class,
            parentColumns = ["id"],
            childColumns = ["soundId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("soundId")],
)
data class LyricsEntity(
    @PrimaryKey val soundId: Long,
    val provider: String,
    val syncedLrc: String? = null,
    val plainText: String? = null,
    val trackName: String? = null,
    val artistName: String? = null,
    val timeScale: Float = 1f,
    val updatedAt: Long = 0,
)
