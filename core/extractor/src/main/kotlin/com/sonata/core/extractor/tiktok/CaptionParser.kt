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
 * song credit second, so explicit forms ("song:", "Artist - Title",
 * "Title by Artist") always win. Free-form lines are only used when they are
 * short and a matching hashtag confirms the identity, which is how edits such
 * as "like that. #dojacat #likethat" are credited without guessing at random
 * social text.
 */
object CaptionParser {

    private val effectWords = linkedMapOf(
        "sped up" to "sped up",
        "speed up" to "sped up",
        "spedup" to "sped up",
        "speedup" to "sped up",
        "slowed + reverb" to "slowed",
        "slowed" to "slowed",
        "nightcore" to "nightcore",
        "reverb" to "reverb",
        "bass boost" to "bass boost",
        "bassboost" to "bass boost",
        "bass boosted" to "bass boost",
        "8d" to "8d",
    )

    private val sentenceOpeners = setOf(
        "pov", "when", "why", "how", "wait", "bro", "nobody", "this", "that",
        "my", "i", "im", "i'm", "me", "he", "she", "they", "we", "you",
        "just", "if", "tell", "tag", "rate", "watch", "listen", "new",
    )

    /**
     * Platform/format tags that are never a song or artist. Keeping this list
     * explicit avoids turning #audio or #viral into a YouTube search.
     */
    private val genericTags = setOf(
        "fyp", "foryou", "foryoupage", "viral", "trending", "trend", "edit",
        "edits", "editaudio", "audio", "sound", "sounds", "music", "song",
        "songs", "tiktok", "tiktokmusic", "tiktokaudio", "slowedsongs",
        "spedupsongs", "nightcore", "reverb", "bassboost", "8d", "blowthisup",
        "getmefamous", "relatable", "memes", "funny", "capcut", "lyrics",
        "lyric", "cover", "duet", "stitch", "newmusic", "trendingaudio",
        "slowed", "spedup", "speedup", "remix", "mashup", "playlist",
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

        // A short caption plus a hashtag that spells the same title is a
        // trustworthy credit: "like that. #dojacat #likethat" -> title + artist.
        // This is still conservative; sentences and vague social captions are
        // never promoted on their own.
        if (result.none { it.source == "caption" }) {
            identityFromCaption(description, hashtags)?.let { add(it.title, it.artist, "caption") }
        }

        // Last resort: a real title-style hashtag, but only when no caption
        // identity was found. Platform tags are filtered out first.
        if (result.none { it.source == "caption" || it.source == "hashtag" }) {
            hashtags.asSequence()
                .map { it.removePrefix("#").replace('_', ' ').trim() }
                .filter { it.length in 3..60 && looksLikeTitle(it) && !isGeneric(it) && !isEffectTag(it) }
                .take(1)
                .forEach { add(it, null, "hashtag") }
        }
        return result
    }

    /** Caption text confirmed by a matching title hashtag. */
    private fun identityFromCaption(description: String, hashtags: List<String>): Credit? {
        val caption = cleanCaption(description)
        if (caption.length !in 3..80 || !looksLikeTitle(caption)) return null
        val tags = hashtags.map { it.removePrefix("#").replace('_', ' ').trim() }
            .filter { it.isNotBlank() && !isGeneric(it) && !isEffectTag(it) }
        if (tags.isEmpty()) return null
        val key = normalized(caption)
        val titleTag = tags.firstOrNull { tag ->
            val candidate = normalized(tag)
            candidate == key || candidate.contains(key) || key.contains(candidate)
        }
        // Without a title hashtag the caption could be a random sentence. A
        // single artist-style tag is not enough proof, so stay conservative.
        if (titleTag == null) return null
        val artistTag = tags.firstOrNull { it != titleTag }
        val artist = artistTag?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { looksLikeArtist(it) }
        return Credit(caption.replaceFirstChar { it.titlecase() }, artist)
    }

    /** First caption line with hashtags, mentions and trailing punctuation removed. */
    private fun cleanCaption(description: String): String {
        val first = description.lineSequence().firstOrNull()?.trim().orEmpty()
        if (first.isBlank()) return ""
        return first
            .replace(Regex("""#\S+"""), " ")
            .replace(Regex("""@\S+"""), " ")
            .replace(Regex("""https?://\S+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trim('.', ',', '!', '?', ';', ':', '-', '–', '—', '"', '\'', '(', ')', '[', ']')
            .trim()
    }

    private fun normalized(value: String): String =
        value.lowercase().filter { it.isLetterOrDigit() }

    private fun isGeneric(tag: String): Boolean = normalized(tag) in genericTags

    private fun isEffectTag(tag: String): Boolean {
        val value = normalized(tag)
        return effectWords.keys.any { normalized(it) in value }
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
        val words = value.split(Regex("""\\s+""")).filter { it.isNotBlank() }
        if (words.size !in 1..10 || value.length > 80) return false
        if (words.first().lowercase().trim('.', ',', '!') in sentenceOpeners) return false
        if (value.contains("http", true) || value.count { it == '#' } > 1) return false
        return true
    }

    private fun looksLikeArtist(value: String): Boolean {
        val words = value.split(Regex("""\\s+""")).filter { it.isNotBlank() }
        if (words.size !in 1..8 || value.length > 60) return false
        val lower = value.lowercase()
        if (lower.startsWith("pov") || lower.startsWith("when ") || lower.startsWith("how ")) return false
        if (lower.contains("http") || lower.contains("@")) return false
        return true
    }
}
