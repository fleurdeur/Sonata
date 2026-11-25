package com.sonata.core.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class NaturalLanguageSearchTest {

    @Test
    fun `parses bpm and duration and mood`() {
        val parsed = NaturalLanguageSearch.parse("slow sad songs under 30 seconds")
        assertEquals("sad", parsed.mood)
        assertEquals(30_000L, parsed.durationMaxMs)
        assertNotNull(parsed.understood)
    }

    @Test
    fun `parses over 140 bpm`() {
        val parsed = NaturalLanguageSearch.parse("hype tracks over 140 bpm")
        assertEquals(140, parsed.bpmMin)
        assertEquals("energetic", parsed.mood)
    }
}
