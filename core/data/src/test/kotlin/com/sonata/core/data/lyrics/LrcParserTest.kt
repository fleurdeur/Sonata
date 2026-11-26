package com.sonata.core.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    @Test
    fun parsesTimestampsAndOffset() {
        val raw = """
            [ar:Someone]
            [offset:+500]
            [00:01.20]First line
            [00:03.45][00:05.00]Repeated
            [00:07]No fraction
        """.trimIndent()
        val lines = LrcParser.parse(raw)
        assertEquals(4, lines.size)
        assertEquals(1_700L, lines[0].timeMs)
        assertEquals(3_950L, lines[1].timeMs)
        assertEquals(5_500L, lines[2].timeMs)
        assertEquals(7_500L, lines[3].timeMs)
        assertEquals("First line", lines[0].text)
    }

    @Test
    fun shiftsTimesForSpedUpTrack() {
        val lines = LrcParser.parse("[00:10.00]Hello\n[00:20.00]World", speed = 1.25f)
        assertEquals(8_000L, lines[0].timeMs)
        assertEquals(16_000L, lines[1].timeMs)
    }

    @Test
    fun stripsEnhancedWordTiming() {
        val lines = LrcParser.parse("[00:02.00]<00:02.00>Hello <00:02.50>there")
        assertEquals("Hello there", lines.single().text)
    }

    @Test
    fun blankAndMetadataOnlyReturnEmpty() {
        assertTrue(LrcParser.parse("[ti:Song]\n[ar:Artist]\n\n").isEmpty())
        assertTrue(LrcParser.parse("").isEmpty())
    }

    @Test
    fun plainTextDropsTags() {
        val plain = LrcParser.plainFromLrc("[00:01.00]One\n[00:02.00]Two")
        assertEquals("One\nTwo", plain)
    }
}
