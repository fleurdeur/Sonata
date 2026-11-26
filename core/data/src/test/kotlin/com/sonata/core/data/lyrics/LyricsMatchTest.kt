package com.sonata.core.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsMatchTest {

    private fun query(
        title: String = "Midnight City (sped up)",
        artist: String? = "M83",
        durationMs: Long = 195_000,
    ) = LyricsQuery(title, artist, durationMs)

    @Test
    fun scores_an_exact_song_and_artist() {
        val score = LyricsMatch.score(
            query = query(),
            trackName = "Midnight City",
            artistName = "M83",
            durationMs = 243_000,
        )
        assertTrue(score >= 0.70f)
    }

    @Test
    fun an_unrelated_title_does_not_match() {
        val score = LyricsMatch.score(
            query = query(),
            trackName = "Completely Different Song",
            artistName = "Another Artist",
            durationMs = 200_000,
        )
        assertTrue(score < 0.30f)
    }

    @Test
    fun a_wrong_duration_is_penalised() {
        val exact = LyricsMatch.score(query(), "Midnight City", "M83", 196_000)
        val wrong = LyricsMatch.score(query(), "Midnight City", "M83", 300_000)
        assertTrue(exact > wrong)
    }

    @Test
    fun search_text_does_not_repeat_the_artist() {
        val value = LyricsQuery("M83 - Midnight City", "M83", 0).searchText
        assertEquals(1, value.split("m83", ignoreCase = true).size - 1)
    }
}
