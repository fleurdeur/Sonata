package com.sonata.core.domain.model

data class Playlist(
    val id: Long = 0,
    val name: String,
    val coverPath: String? = null,
    val coverPreset: String? = null,
    val createdAt: Long = 0,
    val soundCount: Int = 0,
    val totalDurationMs: Long = 0,
    val rules: SmartRules? = null,
)

/** Smart playlist rules: most played, recently added, BPM range, key, mood, tag. */
data class SmartRules(
    val kind: Kind = Kind.MOST_PLAYED,
    val bpmMin: Int? = null,
    val bpmMax: Int? = null,
    val key: MusicalKey? = null,
    val mood: String? = null,
    val tag: String? = null,
    val source: SoundSource? = null,
    val limit: Int = 50,
) {
    enum class Kind { MOST_PLAYED, RECENTLY_ADDED, SHORTEST, BPM_RANGE, KEY, SOURCE, MOOD, TAG }
}
