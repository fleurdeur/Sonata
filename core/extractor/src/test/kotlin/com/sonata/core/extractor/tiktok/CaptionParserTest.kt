package com.sonata.core.extractor.tiktok

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionParserTest {

    @Test
    fun `caption confirmed by matching hashtag becomes a song and artist`() {
        val hints = CaptionParser.hints(
            description = "like that.  #dojacat #editaudio #audio #slowedsongs #likethat #blowthisup #fyp #viral #getmefamous",
            hashtags = listOf(
                "#dojacat", "#editaudio", "#audio", "#slowedsongs", "#likethat",
                "#blowthisup", "#fyp", "#viral", "#getmefamous",
            ),
            soundTitle = "original sound - wang.audios",
            soundAuthor = "wang.audios",
            matchedTitle = null,
            matchedArtist = null,
            isOriginalSound = true,
        )

        assertEquals("Like that", hints.first().title)
        assertEquals("dojacat", hints.first().artist)
        assertEquals("caption", hints.first().source)
    }

    @Test
    fun `vague social caption without a matching hashtag is not a song`() {
        val hints = CaptionParser.hints(
            description = "pov you finally found the song #fyp #viral",
            hashtags = listOf("#fyp", "#viral"),
            soundTitle = "original sound - someone",
            soundAuthor = "someone",
            matchedTitle = null,
            matchedArtist = null,
            isOriginalSound = true,
        )

        assertTrue(hints.isEmpty())
    }

    @Test
    fun `explicit credit still wins over caption guessing`() {
        val hints = CaptionParser.hints(
            description = "late night drive #fyp\nsong: Frank Ocean - Pink + White",
            hashtags = listOf("#fyp", "#pinkwhite"),
            soundTitle = "original sound - someone",
            soundAuthor = "someone",
            matchedTitle = null,
            matchedArtist = null,
            isOriginalSound = true,
        )

        assertEquals("Pink + White", hints.first().title)
        assertEquals("Frank Ocean", hints.first().artist)
    }
}
