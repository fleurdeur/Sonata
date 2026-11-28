package com.sonata.core.extractor.tiktok

/** A possible search identity for the full recording. */
data class TrackHint(
    val title: String,
    val artist: String?,
    /** matched, sound, caption or manual */
    val source: String,
)

/**
 * Conservative caption understanding. A caption is a social post first and a
 * song credit second, so only explicit forms ("song:", "Artist - Title",
 * "Title by Artist") become search hints. Free-form lines such as "pov you..."
 * are never sent to YouTube as a song title.
 */
object CaptionParser {

    private val effectWords = linkedMapOf(
        "sped up" to "sped up",
        "speed up" to "sped up",
        "slowed + reverb" to "slowed",
        "slowed" to "slowed",
        "nightcore" to "nightcore",
        "reverb" to "reverb",
        "bass boost" to "bass boost",
        "bass boosted" to "bass boost",
        "8d" to "8d",
    )

    private val sentenceOpeners = setOf(
        "pov", "when", "why", "how", "wait", "bro", "nobody", "this", "that",
        "my", "i", "im", "i'm", "me", "he", "she", "they", "we", "you",
        "just", "if", "tell", "tag", "rate", "watch", "listen", "new",
    )

    fun hints(
        description: String,
        hashtags: List<String>,
        soundTitle: String?,
        soundAuthor: String?,
        matchedTitle: String?,
        matchedArtist: String?,
        isOriginalSound: Boolean,
    ): List<TrackHint> {
        val result = mutableListOf<TrackHint>()
        fun add(title: String?, artist: String?, source: String) {
            val clean = title?.trim()?.trim('"')?.takeIf { it.length >= 2 } ?: return
            if (clean.equals("original sound", true)) return
            if (result.any { it.title.equals(clean, true) && it.artist.equals(artist, true) }) return
            result += TrackHint(clean.take(120), artist?.trim()?.take(120), source)
        }

        add(matchedTitle, matchedArtist, "matched")
        if (!isOriginalSound) add(cleanSoundTitle(soundTitle), cleanAuthor(soundAuthor), "sound")

        val lines = description.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        for (line in lines) {
            val explicit = Regex(
                "(?i)^(?:song|music|track|now\\s+playing)\\s*[:\\-–—]\\s*(.+)$",
            ).find(line)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            val note = stripNotePrefix(line)
            val value = explicit.ifBlank {
                if (notePrefixes.any { line.startsWith(it) }) note else ""
            }
            if (value.isNotBlank()) {
                parseCredit(value)?.let { add(it.title, it.artist, "caption") }
                continue
            }
            parseCredit(line)?.let { add(it.title, it.artist, "caption") }
        }

        // A hashtag can be a real title when it is written as #SongTitle, but
        // platform tags (#fyp, #viral, #foryou) are not song identities.
        if (result.none { it.source == "caption" }) {
            hashtags.asSequence()
                .map { it.removePrefix("#").replace('_', ' ').trim() }
                .filter { it.length in 3..60 && looksLikeTitle(it) }
                .take(1)
                .forEach { add(it, null, "hashtag") }
        }
        return result
    }

    fun effects(description: String, hashtags: List<String>): Set<String> {
        val text = " " + description.lowercase() + " " + hashtags.joinToString(" ").lowercase() + " "
        return effectWords.filterKeys { text.contains(it) }.values.toSet()
    }

    fun cleanSoundTitle(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var t = raw.trim()
        val suffixes = listOf(" - original sound", " original sound", "(original sound)", "[original sound]")
        suffixes.forEach { suffix -> t = t.replace(suffix, "", ignoreCase = true) }
        return t.trim().trim('"').takeIf { it.isNotBlank() }
    }

    private fun cleanAuthor(raw: String?): String? {
        val value = raw?.trim()?.trim('"') ?: return null
        return value.takeIf { it.isNotBlank() && !it.equals("TikTok", true) && !it.equals("original sound", true) }
    }

    private data class Credit(val title: String, val artist: String?)

    private fun parseCredit(raw: String): Credit? {
        val value = stripNotePrefix(raw.trim())
            .removePrefix("song:").removePrefix("music:").trim()
        if (value.length !in 3..120) return null
        Regex("^(.{2,60}?)\\s+by\\s+(.{2,60})$", RegexOption.IGNORE_CASE).find(value)?.let { m ->
            val title = m.groupValues[1].trim()
            val artist = m.groupValues[2].trim()
            if (looksLikeTitle(title) && looksLikeArtist(artist)) return Credit(title, artist)
        }
        Regex("^(.{2,70}?)\\s+[-–—]\\s+(.{2,70})$").find(value)?.let { m ->
            val left = m.groupValues[1].trim()
            val right = m.groupValues[2].trim()
            if (!looksLikeTitle(right) || !looksLikeArtist(left)) return@let
            return Credit(right, left)
        }
        return null
    }

    private val notePrefixes = listOf("🎵", "🎶", "♫", "♪", "-", "—", " ")

    private fun stripNotePrefix(raw: String): String {
        var value = raw
        while (true) {
            val prefix = notePrefixes.firstOrNull { value.startsWith(it) } ?: return value.trim()
            value = value.removePrefix(prefix)
        }
    }

    private fun looksLikeTitle(value: String): Boolean {
        val words = value.split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (words.size !in 1..10 || value.length > 80) return false
        if (words.first().lowercase().trim('.', ',', '!') in sentenceOpeners) return false
        if (value.contains("http", true) || value.count { it == '#' } > 1) return false
        return true
    }

    private fun looksLikeArtist(value: String): Boolean {
        val words = value.split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (words.size !in 1..8 || value.length > 60) return false
        val lower = value.lowercase()
        if (lower.startsWith("pov") || lower.startsWith("when ") || lower.startsWith("how ")) return false
        if (lower.contains("http") || lower.contains("@")) return false
        return true
    }
}