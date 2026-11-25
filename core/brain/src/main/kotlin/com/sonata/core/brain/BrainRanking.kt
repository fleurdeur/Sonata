package com.sonata.core.brain

import com.sonata.core.domain.model.Sound
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Brain's ranking logic: every suggestion carries an explainable reason and
 * decays as the user rejects or accepts similar ones. Pure functions — unit
 * tested in core:brain tests.
 */
object BrainRanking {

    data class ScoredSuggestion(
        val id: String,
        val headline: String,
        val reason: String,
        val score: Double,
    )

    /** Ranks candidate presets for a clip from spectrum/genre/BPM features. */
    fun rankPresets(sound: Sound, candidates: List<String>): List<ScoredSuggestion> {
        val bpm = sound.bpm ?: 120
        val bassWeight = sound.waveform.take(12).average().takeUnless { it.isNaN() } ?: 0.3
        return candidates.map { name ->
            var score = 0.0
            val reasons = mutableListOf<String>()
            when {
                name.equals("Hardstyle", true) && bpm in 140..160 -> {
                    score += 2.0
                    reasons += "${bpm} BPM in the hardstyle range"
                }
                name.equals("Bass Boost", true) && bassWeight > 0.5 -> {
                    score += 1.5
                    reasons += "heavy low-end energy in the waveform"
                }
                name.equals("Phonk", true) && bpm in 120..150 -> {
                    score += 1.2
                    reasons += "tempo that sits in the phonk pocket"
                }
                name.equals("Lo-fi", true) && sound.durationMs < 45_000 -> {
                    score += 0.8
                    reasons += "short clip that loops well"
                }
                name.equals("Vocal Clarity", true) && sound.mood == "voice" -> {
                    score += 2.5
                    reasons += "detected voice content"
                }
                else -> score += 0.4
            }
            score *= acceptanceDecay(sound.tags.count { it.equals(name, true) })
            ScoredSuggestion(
                id = "preset_${name.lowercase().replace(' ', '_')}_${sound.id}",
                headline = "Try $name on this clip",
                reason = "Suggested because this clip is $bpm BPM with ${if (bassWeight > 0.5) "heavy" else "light"} bass",
                score = score,
            )
        }.sortedByDescending { it.score }
    }

    /** Accepting raises future relevance; rejecting decays the family. */
    fun acceptanceDecay(rejections: Int): Double = 1.0 / (1.0 + rejections * 0.5)

    /** Groups near-duplicates for the clean-up finder. */
    fun findNearDuplicates(
        sounds: List<Sound>,
        hamming: (String, String) -> Int,
    ): List<Pair<Long, Long>> = buildList {
        val withFp = sounds.filter { it.fingerprint != null }
        for (i in withFp.indices) {
            for (j in i + 1 until withFp.size) {
                if (hamming(withFp[i].fingerprint!!, withFp[j].fingerprint!!) <= 8) {
                    add(withFp[i].id to withFp[j].id)
                }
            }
        }
    }

    /** Forgotten favorites: favorited but not played in weeks. */
    fun forgottenFavorites(sounds: List<Sound>, nowMs: Long): List<Sound> =
        sounds.filter { it.isFavorite && (nowMs - it.addedAt) > 14L * 24 * 60 * 60 * 1000 && it.playCount <= 2 }

    /** Next-track recommendation: closest BPM and key on the Camelot wheel. */
    fun suggestNext(sound: Sound, pool: List<Sound>): Sound? =
        pool.filter { it.id != sound.id }
            .maxByOrNull { candidate ->
                val bpmCloseness = 1.0 - kotlin.math.abs((candidate.bpm ?: sound.bpm ?: 120) - (sound.bpm ?: 120)) / 60.0
                val keyCloseness = if (candidate.key != null && sound.key != null) {
                    if (candidate.key!!.camelot == sound.key!!.camelot) 1.0 else 0.5
                } else 0.6
                bpmCloseness * keyCloseness * (1 + candidate.playCount / 100.0)
            }
}

/** Natural-language search: "slow sad songs under 30 seconds" -> structured filters. */
object NaturalLanguageSearch {

    data class ParsedQuery(
        val text: String,
        val bpmMin: Int? = null,
        val bpmMax: Int? = null,
        val durationMaxMs: Long? = null,
        val durationMinMs: Long? = null,
        val mood: String? = null,
        val tag: String? = null,
        val understood: String,
    )

    fun parse(input: String): ParsedQuery {
        val lower = input.lowercase()
        var bpmMax: Int? = null
        var bpmMin: Int? = null
        var durationMaxMs: Long? = null
        var durationMinMs: Long? = null
        var mood: String? = null

        Regex("(\\d+)\\s*bpm").findAll(lower).forEach { m ->
            val n = m.groupValues[1].toInt()
            when {
                lower.contains("over $n bpm") || lower.contains("above $n bpm") -> bpmMin = n
                lower.contains("under $n bpm") || lower.contains("below $n bpm") -> bpmMax = n
                else -> {
                    bpmMax = n + 5
                    bpmMin = n - 5
                }
            }
        }
        Regex("under\\s+(\\d+)\\s*seconds?").find(lower)?.let {
            durationMaxMs = it.groupValues[1].toLong() * 1000
        }
        Regex("over\\s+(\\d+)\\s*seconds?").find(lower)?.let {
            durationMinMs = it.groupValues[1].toLong() * 1000
        }
        mood = when {
            "sad" in lower -> "sad"
            "happy" in lower -> "happy"
            "chill" in lower -> "chill"
            "energetic" in lower || "hype" in lower -> "energetic"
            else -> null
        }
        val understood = buildString {
            bpmMin?.let { append("over $it BPM, ") }
            bpmMax?.let { append("under $it BPM, ") }
            durationMinMs?.let { append("over ${it / 1000} s, ") }
            durationMaxMs?.let { append("under ${it / 1000} s, ") }
            mood?.let { append("mood $it, ") }
            if (isEmpty()) append("keyword match") else setLength(length - 2)
        }
        return ParsedQuery(
            text = input.replace(Regex("\\d+\\s*bpm|under\\s+\\d+\\s+seconds?|over\\s+\\d+\\s+seconds?|sad|happy|chill|energetic|hype"), "")
                .trim(),
            bpmMin, bpmMax, durationMaxMs, durationMinMs, mood, understood = understood,
        )
    }
}
