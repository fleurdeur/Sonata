package com.sonata.core.brain

import com.sonata.core.domain.repository.BrainRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Remix Lab profile definitions — Slowed + Reverb, Nightcore and friends.
 * Each is a non-destructive recipe saved as a new version of the clip.
 */
@Serializable
data class RemixProfile(
    val name: String,
    val speed: Float = 1f,
    val pitchSemitones: Float = 0f,
    val reverbMix: Float = 0f,
    val lowShelfGainDb: Float = 0f,
    val highCutHz: Float? = null,
    val chorus: Float = 0f,
) {
    companion object {
        val SLOWED_REVERB = RemixProfile(
            name = "Slowed + Reverb",
            speed = 0.82f, pitchSemitones = -1f, reverbMix = 0.45f,
            lowShelfGainDb = 2f, highCutHz = 14000f,
        )
        val NIGHTCORE = RemixProfile(name = "Nightcore", speed = 1.25f, pitchSemitones = 2.5f)
        val VAPORWAVE = RemixProfile(
            name = "Vaporwave",
            speed = 0.75f, pitchSemitones = -3f, reverbMix = 0.6f,
            lowShelfGainDb = 4f, highCutHz = 10000f, chorus = 0.4f,
        )
        val DAYCORE = RemixProfile(name = "Daycore", speed = 0.85f, pitchSemitones = -2f, reverbMix = 0.3f)
        val ALL = listOf(SLOWED_REVERB, NIGHTCORE, VAPORWAVE, DAYCORE)

        fun fromJson(json: String): RemixProfile? = runCatching {
            Json { ignoreUnknownKeys = true }.decodeFromString<RemixProfile>(json)
        }.getOrNull()
    }
}

/** The preset advisor: which EQ presets and Remix profiles fit a clip. */
@Singleton
class PresetAdvisor @Inject constructor(
    private val brainRepository: BrainRepository,
) {
    fun advise(sound: com.sonata.core.domain.model.Sound): List<com.sonata.core.brain.BrainRanking.ScoredSuggestion> =
        BrainRanking.rankPresets(
            sound,
            listOf("Hardstyle", "Phonk", "Bass Boost", "Lo-fi", "Vocal Clarity", "Slowed + Reverb", "Nightcore"),
        )
}
