package com.sonata.core.data.lyrics

import com.sonata.core.domain.model.LyricLine
import com.sonata.core.domain.model.LyricWord

/**
 * Parses standard LRC (multiple timestamps and [offset:]) plus enhanced LRC
 * word timestamps. Enhanced LRC supplies real word-level karaoke timing; when
 * it is absent the line still animates as a whole.
 */
object LrcParser {

    private val timeTag = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val offsetTag = Regex("""\[offset:\s*([+-]?\d+)]""", RegexOption.IGNORE_CASE)
    private val wordTag = Regex("""<(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?>([^<]*)""")

    fun parse(raw: String, speed: Float = 1f): List<LyricLine> {
        if (raw.isBlank()) return emptyList()
        val rate = if (speed.isFinite() && speed > 0.1f) speed else 1f
        val offsetMs = offsetTag.find(raw)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
        val out = ArrayList<LyricLine>()
        raw.lineSequence().forEach { line ->
            val stamps = timeTag.findAll(line).toList()
            if (stamps.isEmpty()) return@forEach
            val lineTimes = stamps.mapNotNull { stamp ->
                val minutes = stamp.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                val seconds = stamp.groupValues[2].toLongOrNull() ?: return@mapNotNull null
                val fraction = fractionMs(stamp.groupValues.getOrNull(3).orEmpty())
                minutes * 60_000 + seconds * 1_000 + fraction + offsetMs
            }
            if (lineTimes.isEmpty()) return@forEach

            val wordMatches = wordTag.findAll(line).toList()
            val withoutTime = timeTag.replace(line, "")
            val plainText = wordTag.replace(withoutTime) { match -> match.groupValues[4] }.trim()
            if (plainText.isBlank()) return@forEach

            // Word tags are absolute in standard enhanced LRC. A few
            // generators emit offsets from the line start instead; detect that
            // when the first word is earlier than the line timestamp.
            val absoluteWords = wordMatches.mapNotNull { match ->
                val minutes = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                val seconds = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
                val fraction = fractionMs(match.groupValues.getOrNull(3).orEmpty())
                val text = match.groupValues.getOrNull(4).orEmpty()
                if (text.isBlank()) return@mapNotNull null
                (minutes * 60_000 + seconds * 1_000 + fraction) to text
            }
            val relativeWords = absoluteWords.isNotEmpty() &&
                absoluteWords.first().first < (lineTimes.first() - offsetMs)
            lineTimes.forEach { original ->
                val words = absoluteWords.map { (wordTime, text) ->
                    val absolute = if (relativeWords) {
                        original - offsetMs + wordTime
                    } else {
                        wordTime + offsetMs
                    }
                    LyricWord((absolute / rate).toLong().coerceAtLeast(0), text.trim())
                }
                out += LyricLine(
                    timeMs = (original / rate).toLong().coerceAtLeast(0),
                    text = plainText,
                    words = words,
                )
            }
        }
        return out.sortedWith(compareBy({ it.timeMs }, { it.text }))
            .distinctBy { it.timeMs to it.text }
    }

    /** Converts an LRC body to readable text when only plain lines are shown. */
    fun plainFromLrc(raw: String): String = raw.lineSequence()
        .map { wordTag.replace(timeTag.replace(it, ""), "").trim() }
        .filter { it.isNotBlank() }
        .joinToString("\n")

    private fun fractionMs(raw: String): Long = when (raw.length) {
        0 -> 0L
        1 -> raw.toLongOrNull()?.times(100) ?: 0L
        2 -> raw.toLongOrNull()?.times(10) ?: 0L
        else -> raw.take(3).toLongOrNull() ?: 0L
    }
}
