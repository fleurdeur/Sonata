package com.sonata.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "playlists", indices = [Index("createdAt")])
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val coverPath: String? = null,
    val coverPreset: String? = null,
    val createdAt: Long = 0,
    val smartRulesJson: String? = null,
)

@Entity(
    tableName = "playlist_sounds",
    primaryKeys = ["playlistId", "soundId"],
    indices = [Index("playlistId", "position")],
)
data class PlaylistSoundCrossRef(
    val playlistId: Long,
    val soundId: Long,
    val position: Int = 0,
)

@Entity(tableName = "mixes")
data class MixEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val autoMix: Boolean = false,
)

@Entity(tableName = "transitions", indices = [Index("mixId")])
data class TransitionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mixId: Long = 0,
    val trackIndex: Int = 0,
    val type: String = "AUTO",
    val lengthMs: Long = 4000,
    val curve: String = "S_CURVE",
    val intensity: Float = 0.7f,
)
