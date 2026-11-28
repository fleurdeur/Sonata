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
 * song. Edits are removed as whole words only, so "Audioslave" never becomes
 * "slave" and "Cleansing" never becomes "sing". Rejections are whole-word
 * matches on the video title, never on the channel name: "Recovery" is not a
 * cover and "Loophole" is not a loop.
 */
object FullTrackMatch {

    private val noise = listOf(
        "sped up", "speed up", "slowed down", "slowed + reverb", "slowed",
        "nightcore", "bass boosted", "bass boost", "8d audio", "8d",
        "tiktok", "tik tok", "lyrics", "lyric video", "official audio",
        "official music video", "official video", "visualizer", "audio",
        "clean", "explicit", "hd", "hq", "mv", "remix", "edit",
    ).sortedByDescending { it.length }

    private val rejected = listOf(
        "cover", "covered", "karaoke", "instrumental", "backing track", "reaction",
        "tutorial", "how to", "learn", "lesson", "remake", "ai cover", "ai version",
        "mashup", "medley", "1 hour", "one hour", "10 hours", "extended", "loop",
        "ringtone", "notification", "sound effect", "shorts", "teaser", "trailer",
        "live at", "live from", "concert", "acoustic version", "piano version",
        "orchestral", "slowed + reverb", "nightcore version",
    ).sortedByDescending { it.length }

    private val edited = listOf(
        "sped up", "speed up", "nightcore", "slowed", "reverb", "bass boosted", "8d",
    )

    /** Removes edit tags, emoji, hashtags and punctuation without corrupting words. */
    fun cleanTitle(raw: String): String {
        var value = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        value = value.replace(Regex("""[#@]\S+"""), " ")
        // Only discard bracket groups that actually contain an edit tag. A
        // legitimate parenthetical, such as "(I Can't Get No) Satisfaction", stays.
        value = value.replace(Regex("""[\(\[\{]([^\)\]\}]{0,80})[\)\]\}]""")) { match ->
            val inner = match.groupValues[1].lowercase()
            if (noise.any { containsWhole(inner, it) } || edited.any { containsWhole(inner, it) }) {
                " "
            } else {
                " " + match.groupValues[1] + " "
            }
        }
        value = value.replace(Regex("""[^\p{L}\p{N}\s'&+-]"""), " ")
        value = value.replace(Regex("""\s+"""), " ").trim()
        var lower = value.lowercase()
        for (tag in noise) lower = removeWhole(lower, tag)
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

    /** Search strings, original recording first, with and without its artist. */
    fun queries(title: String, artist: String?): List<String> {
        val core = cleanTitle(title)
        val who = cleanArtist(artist)
        val withArtist = if (who != null && !core.contains(who, ignoreCase = true)) {
            who + " " + core
        } else {
            core
        }
        return listOf(
            withArtist + " official audio",
            core + " official audio",
            withArtist,
            core,
        ).map { it.trim() }
            .filter { it.length >= 2 }
            .distinct()
    }

    fun normalized(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase()
            .replace(Regex("""[^\p{L}\p{N}\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    /** Whole-word rejection on the title only; channel names never veto a song. */
    fun isRejected(title: String, author: String? = null): Boolean {
        val haystack = normalized(title)
        return rejected.any { containsWhole(haystack, it) }
    }

    /**
     * 0..1 score. [clipDurationMs] is the TikTok clip length; a full song must
     * be comfortably longer than the clip, otherwise it is just the same loop.
     * A one-word title is only accepted when the artist also agrees.
     */
    fun score(
        result: SearchResult,
        title: String,
        artist: String?,
        clipDurationMs: Long,
        targetDurationMs: Long? = null,
    ): Float {
        if (result.id.isBlank() || result.title.isBlank()) return 0f
        if (result.durationMs < 45_000) return 0f
        if (isRejected(result.title)) return 0f
        val core = normalized(cleanTitle(title))
        val coreWords = core.split(' ').filter { it.length > 1 }.distinct()
        if (coreWords.isEmpty()) return 0f
        val candidate = normalized(result.title + " " + result.author.orEmpty())
        val candidateWords = candidate.split(' ').filter { it.isNotBlank() }.toSet()
        val overlap = coreWords.count { it in candidateWords }.toFloat() / coreWords.size

        val who = cleanArtist(artist)?.let(::normalized)
        val artistWords = who?.split(' ')?.filter { it.length > 1 }.orEmpty()
        val author = normalized(result.author.orEmpty())
        val artistMatch = artistWords.isNotEmpty() && artistWords.all { word ->
            author.contains(word) || candidateWords.contains(word)
        }
        // "Love" against any video containing the word is not a match.
        if (coreWords.size == 1 && !artistMatch) return 0f

        var score = overlap * 0.62f
        if (artistMatch) score += 0.16f
        val titleLower = normalized(result.title)
        if (containsWhole(titleLower, "official audio")) score += 0.09f
        if (author.endsWith("topic")) score += 0.10f
        if (titleLower.contains("vevo")) score += 0.05f
        if (edited.any { containsWhole(titleLower, it) }) score -= 0.12f
        if (containsWhole(titleLower, "remix")) score -= 0.10f
        if (containsWhole(titleLower, "lyrics")) score -= 0.06f

        val duration = result.durationMs
        if (duration in 45_000..900_000) {
            if (targetDurationMs != null && targetDurationMs > 0) {
                val delta = abs(duration - targetDurationMs).toFloat() / targetDurationMs
                score += 0.12f * (1f - delta.coerceIn(0f, 1f))
            } else if (duration > clipDurationMs + 20_000) {
                score += 0.10f
            }
        } else {
            score -= 0.25f
        }
        if (clipDurationMs > 0 && duration in 1..(clipDurationMs + 4_000)) score -= 0.35f
        return score.coerceIn(0f, 1f)
    }

    /** Rank all usable results together instead of taking the first search page. */
    fun rank(
        results: List<SearchResult>,
        title: String,
        artist: String?,
        clipDurationMs: Long,
        limit: Int = 5,
    ): List<FullTrackCandidate> = results
        .asSequence()
        .filter { it.durationMs >= 45_000 }
        .map { result ->
            val value = score(result, title, artist, clipDurationMs)
            result to value
        }
        .filter { (_, value) -> value >= 0.46f }
        .sortedByDescending { (_, value) -> value }
        .distinctBy { (result, _) -> result.id }
        .take(limit)
        .map { (result, value) ->
            FullTrackCandidate(
                videoId = result.id,
                title = result.title,
                artist = cleanArtist(result.author),
                durationMs = result.durationMs,
                thumbnailUrl = result.thumbnailUrl,
                score = value,
            )
        }
        .toList()

    fun best(
        results: List<SearchResult>,
        title: String,
        artist: String?,
        clipDurationMs: Long,
    ): FullTrackCandidate? = rank(results, title, artist, clipDurationMs, 1).firstOrNull()

    private fun containsWhole(haystack: String, needle: String): Boolean {
        val escaped = Regex.escape(needle.lowercase())
        return Regex("""(?<![\p{L}\p{N}])$escaped(?![\p{L}\p{N}])""").containsMatchIn(haystack)
    }

    private fun removeWhole(value: String, needle: String): String {
        val escaped = Regex.escape(needle.lowercase())
        return value.replace(Regex("""(?<![\p{L}\p{N}])$escaped(?![\p{L}\p{N}])"""), " ")
    }
}