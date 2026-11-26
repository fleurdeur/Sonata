package com.sonata.core.extractor.bitchord

import com.sonata.core.domain.model.SearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FullTrackMatchTest {

    private fun result(
        id: String,
        title: String,
        author: String?,
        durationMs: Long = 210_000,
    ) = SearchResult(
        id = id,
        title = title,
        author = author,
        durationMs = durationMs,
        thumbnailUrl = null,
        providerId = "youtube",
    )

    @Test
    fun strips_edit_tags_from_the_search_title() {
        val clean = FullTrackMatch.cleanTitle("Midnight City (sped up) [TikTok remix] #fyp")
        assertTrue(clean.contains("midnight"))
        assertTrue(clean.contains("city"))
        assertTrue(!clean.contains("sped"))
        assertTrue(!clean.contains("tiktok"))
    }

    @Test
    fun searches_for_the_original_recording_first() {
        val queries = FullTrackMatch.queries("Midnight City (sped up)", "M83")
        assertTrue(queries.first().contains("official audio"))
    }

    @Test
    fun rejects_covers_loops_and_hour_edits() {
        assertTrue(FullTrackMatch.isRejected("Midnight City (cover)", "Random Singer"))
        assertTrue(FullTrackMatch.isRejected("Midnight City 1 hour loop", "Loops"))
        assertTrue(FullTrackMatch.isRejected("Midnight City live at Coachella", "M83"))
    }

    @Test
    fun official_audio_beats_a_short_clip() {
        val title = "Midnight City (sped up)"
        val results = listOf(
            result("clip", "Midnight City sped up", "tiktok edits", 32_000),
            result("official", "Midnight City", "M83 - Topic", 243_000),
            result("cover", "Midnight City (cover)", "Some Cover Band", 240_000),
        )
        val best = FullTrackMatch.best(results, title, "M83", 30_000)
        assertNotNull(best)
        assertEquals("official", best!!.videoId)
        assertTrue(best.score >= 0.52f)
    }

    @Test
    fun a_forty_second_upload_is_not_a_full_song() {
        val results = listOf(
            result("short", "Midnight City", "M83", 40_000),
        )
        assertNull(FullTrackMatch.best(results, "Midnight City", "M83", 30_000))
    }

    @Test
    fun a_clip_of_the_same_length_is_not_a_full_song() {
        val title = "Midnight City"
        val results = listOf(
            result("same", "Midnight City", "M83", 31_000),
        )
        val best = FullTrackMatch.best(results, title, "M83", 30_000)
        assertNull(best)
    }
}