package com.sonata.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "presets")
data class PresetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val category: String = "MINE",
    val description: String = "",
    val bandsJson: String = "[]",
    val compressorJson: String? = null,
    val limiterJson: String? = null,
    val effects: String = "",
    val isFavorite: Boolean = false,
)

@Entity(tableName = "import_jobs")
data class ImportJobEntity(
    @PrimaryKey val id: String,
    val stage: String = "QUEUED",
    val progress: Float = 0f,
    val title: String = "",
    val sourceUri: String = "",
    val error: String? = null,
    val soundId: Long? = null,
    val failure: String? = null,
    val diagnostics: String? = null,
    val attempt: Int = 0,
    val fullSongVerified: Boolean? = null,
    val matchConfidence: Float? = null,
)
