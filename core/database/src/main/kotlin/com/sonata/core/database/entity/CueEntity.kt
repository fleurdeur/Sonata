package com.sonata.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A saved moment in one recording: hot cue, memory or short loop. */
@Entity(
    tableName = "cues",
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
data class CueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val soundId: Long,
    val positionMs: Long,
    val endMs: Long? = null,
    val label: String,
    val kind: String,
    val createdAt: Long,
)