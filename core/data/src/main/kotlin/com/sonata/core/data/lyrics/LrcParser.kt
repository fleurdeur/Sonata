package com.sonata.core.data.lyrics

import com.sonata.core.domain.model.LyricLine

/** Parses standard LRC (including multiple timestamps and [offset:]). */
object LrcParser {

    private val timeTag = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val offsetTag = Regex("""\[offset:\s*([+-]?\d+)]""", RegexOption.IGNORE_CASE)
    private val enhancedTag = Regex("""<\d{1,2}:\d{2}(?:[.:]\d{1,3})?>""")

    fun parse(raw: String, speed: Float = 1f): List<LyricLine> {
        if (raw.isBlank()) return emptyList()
        val rate = if (speed.isFinite() && speed > 0.1f) speed else 1f
        val offsetMs = offsetTag.find(raw)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
        val out = ArrayList<LyricLine>()
        raw.lineSequence().forEach { line ->
            val stamps = timeTag.findAll(line).toList()
            if (stamps.isEmpty()) return@forEach
            val text = enhancedTag.replace(line, "")
                .replace(timeTag, "")
                .trim()
            if (text.isBlank()) return@forEach
            stamps.forEach stampLoop@{ stamp ->
                val minutes = stamp.groupValues[1].toLongOrNull() ?: return@stampLoop
                val seconds = stamp.groupValues[2].toLongOrNull() ?: return@stampLoop
                val fractionRaw = stamp.groupValues[3]
                val fraction = when (fractionRaw.length) {
                    0 -> 0L
                    1 -> fractionRaw.toLong() * 100
                    2 -> fractionRaw.toLong() * 10
                    else -> fractionRaw.take(3).toLong()
                }
                val original = minutes * 60_000 + seconds * 1_000 + fraction + offsetMs
                val shifted = (original / rate).toLong().coerceAtLeast(0)
                out += LyricLine(shifted, text)
            }
        }
        return out.sortedWith(compareBy({ it.timeMs }, { it.text }))
            .distinctBy { it.timeMs to it.text }
    }

    /** Converts an LRC body to readable text when only plain lines are shown. */
    fun plainFromLrc(raw: String): String = raw.lineSequence()
        .map { enhancedTag.replace(timeTag.replace(it, ""), "").trim() }
        .filter { it.isNotBlank() }
        .joinToString("\n")
}