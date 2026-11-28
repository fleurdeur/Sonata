package com.sonata.core.domain.model

enum class SoundSource { LOCAL, LINK, TIKTOK, RECORDING, SHARE, WATCHED_FOLDER, STREAMING }

enum class ImportStatus { QUEUED, DOWNLOADING, EXTRACTING, ANALYZING, READY, FAILED }

/** Which playable file a TikTok import should use. */
enum class PlaybackVariant { CLIP, FULL }

enum class MusicalKey(val display: String, val camelot: String) {
    C("C", "8B"), G("G", "9B"), D("D", "10B"), A("A", "11B"), E("E", "12B"),
    B("B", "1B"), F_SHARP("F#", "2B"), C_SHARP("Db", "3B"), G_SHARP("Ab", "4B"),
    D_SHARP("Eb", "5B"), A_SHARP("Bb", "6B"), F("F", "7B"),
    A_MINOR("Am", "8A"), E_MINOR("Em", "9A"), B_MINOR("Bm", "10A"),
    F_SHARP_MINOR("F#m", "11A"), C_SHARP_MINOR("C#m", "12A"), G_SHARP_MINOR("G#m", "1A"),
    D_SHARP_MINOR("Ebm", "2A"), A_SHARP_MINOR("Bbm", "3A"), F_MINOR("Fm", "4A"),
    C_MINOR("Cm", "5A"), G_MINOR("Gm", "6A"), D_MINOR("Dm", "7A");

    val isMinor: Boolean get() = name.endsWith("_MINOR")

    companion object {
        /** Pitch class (0 = C) to key name, independent of this enum's order. */
        private val BY_PITCH_CLASS = arrayOf(
            C, C_SHARP, D, D_SHARP, E, F, F_SHARP, G, G_SHARP, A, A_SHARP, B,
        )
        private val MINOR_BY_PITCH_CLASS = arrayOf(
            C_MINOR, C_SHARP_MINOR, D_MINOR, D_SHARP_MINOR, E_MINOR, F_MINOR,
            F_SHARP_MINOR, G_MINOR, G_SHARP_MINOR, A_MINOR, A_SHARP_MINOR, B_MINOR,
        )

        fun fromPitchClass(pitchClass: Int, minor: Boolean = false): MusicalKey {
            val index = ((pitchClass % 12) + 12) % 12
            return if (minor) MINOR_BY_PITCH_CLASS[index] else BY_PITCH_CLASS[index]
        }
    }
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
    /** The original TikTok clip, kept alongside a verified full song. */
    val clipFilePath: String? = null,
    /** The effect-matched full recording, when one was proven. */
    val fullSongFilePath: String? = null,
    /** Start of the TikTok moment inside the rendered full song. */
    val matchOffsetMs: Long = 0,
    val matchConfidence: Float? = null,
    /** Speed already baked into the rendered files. */
    val effectSpeed: Float = 1f,
    val activeVariant: PlaybackVariant = PlaybackVariant.FULL,
    val tiktokMusicId: String? = null,
    val tiktokSoundName: String? = null,
    val tiktokCaption: String? = null,
) {
    val hasVariants: Boolean
        get() = !clipFilePath.isNullOrBlank() && !fullSongFilePath.isNullOrBlank()

    override fun equals(other: Any?): Boolean = other is Sound && other.id == id
    override fun hashCode(): Int = id.hashCode()
}

data class Tag(val name: String, val soundCount: Int = 0)
