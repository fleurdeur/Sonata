package com.sonata.core.domain.model

enum class SoundSource { LOCAL, LINK, RECORDING, SHARE, WATCHED_FOLDER, STREAMING }

enum class ImportStatus { QUEUED, DOWNLOADING, EXTRACTING, ANALYZING, READY, FAILED }

enum class MusicalKey(val display: String, val camelot: String) {
    C("C", "8B"), G("G", "9B"), D("D", "10B"), A("A", "11B"), E("E", "12B"),
    B("B", "1B"), F_SHARP("F#", "2B"), C_SHARP("Db", "3B"), G_SHARP("Ab", "4B"),
    D_SHARP("Eb", "5B"), A_SHARP("Bb", "6B"), F("F", "7B"),
}

data class Sound(
    val id: Long = 0,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val source: SoundSource = SoundSource.LOCAL,
    val durationMs: Long = 0,
    val sampleRate: Int = 44100,
    val channels: Int = 2,
    val bpm: Int? = null,
    val key: MusicalKey? = null,
    val loudnessLufs: Float? = null,
    val mood: String? = null,
    val genre: String? = null,
    val isFavorite: Boolean = false,
    val playCount: Int = 0,
    val loopCount: Int = 0,
    val addedAt: Long = 0,
    val filePath: String = "",
    val coverPath: String? = null,
    val waveform: FloatArray = FloatArray(0),
    val tags: List<String> = emptyList(),
    val fingerprint: String? = null,
    val importStatus: ImportStatus = ImportStatus.READY,
) {
    override fun equals(other: Any?): Boolean = other is Sound && other.id == id
    override fun hashCode(): Int = id.hashCode()
}

data class Tag(val name: String, val soundCount: Int = 0)
