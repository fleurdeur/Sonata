package com.sonata.core.domain.model

/** One timed lyric line, in the timeline of the finished track. */
data class LyricLine(
    val timeMs: Long,
    val text: String,
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
) {
    val synced: Boolean get() = lines.isNotEmpty()
    val isEmpty: Boolean get() = lines.isEmpty() && plainText.isBlank()

    /** The line that is playing at [positionMs], or -1 before the first one. */
    fun indexAt(positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        var low = 0
        var high = lines.size - 1
        var answer = -1
        while (low <= high) {
            val mid = (low + high) / 2
            if (lines[mid].timeMs <= positionMs) {
                answer = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return answer
    }
}
