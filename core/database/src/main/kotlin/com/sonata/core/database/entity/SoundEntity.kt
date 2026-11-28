package com.sonata.core.database.entity

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

@Entity(
    tableName = "sounds",
    indices = [
        Index("addedAt"),
        Index("source"),
        Index("fingerprint", unique = true),
    ],
)
data class SoundEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val source: String = "LOCAL",
    val durationMs: Long = 0,
    val sampleRate: Int = 44100,
    val channels: Int = 2,
    val bpm: Int? = null,
    val key: String? = null,
    val loudnessLufs: Float? = null,
    val mood: String? = null,
    val genre: String? = null,
    val isFavorite: Boolean = false,
    val playCount: Int = 0,
    val loopCount: Int = 0,
    val addedAt: Long = 0,
    val filePath: String = "",
    val coverPath: String? = null,
    val waveformJson: String = "[]",
    val tags: String = "",
    val fingerprint: String? = null,
    val importStatus: String = "READY",
    val clipFilePath: String? = null,
    val fullSongFilePath: String? = null,
    val matchOffsetMs: Long = 0,
    val matchConfidence: Float? = null,
    val effectSpeed: Float = 1f,
    val activeVariant: String = "FULL",
    val tiktokMusicId: String? = null,
    val tiktokSoundName: String? = null,
    val tiktokCaption: String? = null,
)

@Fts4(contentEntity = SoundEntity::class)
@Entity(tableName = "sounds_fts")
data class SoundFtsEntity(
    val title: String,
    val artist: String?,
    val tags: String,
)

fun List<Float>.encodeWaveform(): String =
    Json.encodeToString(ListSerializer(Float.serializer()), this)

fun decodeWaveform(json: String): List<Float> = runCatching {
    Json.decodeFromString(ListSerializer(Float.serializer()), json)
}.getOrDefault(emptyList())
