package com.sonata.core.extractor.bitchord

import com.sonata.core.domain.model.SearchResult
import java.text.Normalizer
import kotlin.math.abs

/** A full-song video found on YouTube for a TikTok clip. */
data class FullTrackCandidate(
    val videoId: String,
    val title: String,
    val artist: String?,
    val durationMs: Long,
    val thumbnailUrl: String?,
    val score: Float,
)

/**
 * Pure matching rules for turning a TikTok sound into the *original* full
 * song. TikTok titles are full of edit tags; YouTube is full of covers, live
 * versions, loops and hour-long edits. A candidate only wins when its title,
 * channel and duration all look like the real recording.
 */
object FullTrackMatch {

    private val noise = listOf(
        "sped up", "speed up", "slowed", "slowed down", "nightcore", "reverb",
        "bass boosted", "8d audio", "8d", "tiktok", "tik tok", "lyrics",
        "lyric video", "official audio", "official video", "official music video",
        "audio", "visualizer", "clean", "explicit", "hd", "hq", "mv",
    )

    private val rejected = listOf(
        "cover", "covered", "karaoke", "instrumental", "backing track", "reaction",
        "tutorial", "how to", "learn", "lesson", "remake", "ai cover", "ai version",
        "mashup", "medley", "1 hour", "one hour", "10 hours", "extended", "loop",
        "ringtone", "notification", "sound effect", "shorts", "teaser", "trailer",
        "live at", "live from", "concert", "acoustic version", "piano version",
        "orchestral", "slowed + reverb", "nightcore version",
    )

    /** Removes edit tags, emoji, punctuation and bracketed noise. */
    fun cleanTitle(raw: String): String {
        var value = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        value = value.replace(Regex("""[\(\[\{][^)\]\}]{0,40}[\)\]\}]"""), " ")
        value = value.replace(Regex("""[#@]\S+"""), " ")
        value = value.replace(Regex("""[^\p{L}\p{N}\s'&+-]"""), " ")
        value = value.replace(Regex("""\s+"""), " ").trim()
        var lower = value.lowercase()
        for (tag in noise) {
            lower = lower.replace(tag, " ")
        }
        value = lower.replace(Regex("""\s+"""), " ").trim()
        return value.ifBlank { raw.trim() }
    }

    fun cleanArtist(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return raw
            .substringBefore(" - Topic")
            .substringBefore("VEVO")
            .replace(Regex("""[^\p{L}\p{N}\s'&.-]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private val edited = listOf(
        "sped up", "speed up", "nightcore", "slowed", "reverb", "bass boosted", "8d",
    )

    /** Search strings, original recording first. */
    fun queries(title: String, artist: String?): List<String> {
        val core = cleanTitle(title)
        val who = cleanArtist(artist)
        val first = if (who != null && !core.contains(who, ignoreCase = true)) {
            who + " " + core
        } else {
            core
        }
        return listOf(core + " official audio", first, core)
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinct()
    }

    fun normalized(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase()
            .replace(Regex("""[^\p{L}\p{N}\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    fun isRejected(title: String, author: String?): Boolean {
        val haystack = normalized(title + " " + author.orEmpty())
        return rejected.any { haystack.contains(normalized(it)) }
    }

    /**
     * 0..1 score. [clipDurationMs] is the TikTok clip length; a full song must
     * be comfortably longer than the clip, otherwise it is just the same loop.
     */
    fun score(
        result: SearchResult,
        title: String,
        artist: String?,
        clipDurationMs: Long,
        targetDurationMs: Long? = null,
    ): Float {
        if (result.id.isBlank() || result.title.isBlank()) return 0f
        // A result without a real length cannot be proven to be the full song.
        if (result.durationMs < 45_000) return 0f
        if (isRejected(result.title, result.author)) return 0f
        val core = normalized(cleanTitle(title))
        val coreWords = core.split(' ').filter { it.length > 1 }.toSet()
        if (coreWords.isEmpty()) return 0f
        val candidate = normalized(result.title + " " + result.author.orEmpty())
        val candidateWords = candidate.split(' ').filter { it.length > 1 }.toSet()
        val overlap = coreWords.count { word ->
            candidateWords.any { it == word || (word.length >= 4 && it.contains(word)) }
        }.toFloat() / coreWords.size

        val who = cleanArtist(artist)?.let(::normalized)
        val author = normalized(result.author.orEmpty())
        val artistMatch = who != null && who.split(' ').filter { it.length > 1 }.any { word ->
            author.contains(word) || candidate.contains(word)
        }

        var score = overlap * 0.58f
        if (artistMatch) score += 0.18f
        if (candidate.contains("official audio")) score += 0.08f
        if (author.endsWith("topic")) score += 0.10f
        if (candidate.contains("vevo")) score += 0.06f
        // An already-edited upload would get the clip's effect applied twice.
        if (edited.any { normalized(result.title).contains(it) }) score -= 0.12f

        val duration = result.durationMs
        if (duration in 45_000..900_000) {
            when {
                targetDurationMs != null && targetDurationMs > 0 -> {
                    val delta = abs(duration - targetDurationMs).toFloat() / targetDurationMs
                    score += (0.12f * (1f - delta.coerceIn(0f, 1f)))
                }
                duration > clipDurationMs + 20_000 -> score += 0.10f
            }
        } else {
            score -= 0.25f
        }

        // The clip itself (or a slightly longer upload of it) is not a full song.
        if (clipDurationMs > 0 && duration in 1..(clipDurationMs + 4_000)) score -= 0.35f
        return score.coerceIn(0f, 1f)
    }

    fun best(
        results: List<SearchResult>,
        title: String,
        artist: String?,
        clipDurationMs: Long,
    ): FullTrackCandidate? = results
        .mapNotNull { result ->
            if (result.durationMs < 45_000) return@mapNotNull null
            val value = score(result, title, artist, clipDurationMs)
            if (value < 0.46f) {
                null
            } else {
                FullTrackCandidate(
                    videoId = result.id,
                    title = result.title,
                    artist = cleanArtist(result.author),
                    durationMs = result.durationMs,
                    thumbnailUrl = result.thumbnailUrl,
                    score = value,
                )
            }
        }
        .maxByOrNull { it.score }
}