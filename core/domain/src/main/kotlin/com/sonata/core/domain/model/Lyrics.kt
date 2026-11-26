package com.sonata.core.domain.model

/** One timed word inside an enhanced LRC line. */
data class LyricWord(
    val timeMs: Long,
    val text: String,
)

/** One timed lyric line, in the timeline of the finished track. */
data class LyricLine(
    val timeMs: Long,
    val text: String,
    val words: List<LyricWord> = emptyList(),
)

/**
 * Lyrics for a sound. [synced] is true when [lines] carry timestamps; plain
 * lyrics still render, just without the karaoke-style highlight.
 */
data class Lyrics(
    val soundId: Long,
    val provider: String,
    val lines: List<LyricLine> = emptyList(),
    val plainText: String = "",
    val trackName: String? = null,
    val artistName: String? = null,
    val speed: Float = 1f,
    /** Per-track calibration. Positive values make lyrics highlight later. */
    val offsetMs: Long = 0,
    /** Original enhanced LRC, when available, kept for exact backup/restore. */
    val rawLrc: String? = null,
) {
    val synced: Boolean get() = lines.isNotEmpty()
    val isEmpty: Boolean get() = lines.isEmpty() && plainText.isBlank()
    val hasWordTiming: Boolean get() = lines.any { it.words.size >= 2 }

    /** The line that is playing at [positionMs], or -1 before the first one. */
    fun indexAt(positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        val effective = positionMs - offsetMs
        var low = 0
        var high = lines.size - 1
        var answer = -1
        while (low <= high) {
            val mid = (low + high) / 2
            if (lines[mid].timeMs <= effective) {
                answer = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return answer
    }

    /** Index of the highlighted word in [line], or -1. */
    fun wordIndexAt(line: LyricLine, positionMs: Long): Int {
        if (line.words.isEmpty()) return -1
        val effective = positionMs - offsetMs
        var answer = -1
        line.words.forEachIndexed { index, word ->
            if (word.timeMs <= effective) answer = index
        }
        return answer
    }
}
