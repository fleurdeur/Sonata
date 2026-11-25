package com.sonata.core.brain

import com.sonata.core.domain.model.Sound
import com.sonata.core.domain.model.MusicalKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainRankingTest {

    private fun sound(bpm: Int?, id: Long = 1) = Sound(
        id = id, title = "clip", bpm = bpm, durationMs = 30_000,
        waveform = FloatArray(64) { if (it < 12) 0.9f else 0.2f },
    )

    @Test
    fun `hardstyle is suggested first for a 142 bpm heavy-bass clip`() {
        val ranked = BrainRanking.rankPresets(
            sound(142),
            listOf("Lo-fi", "Hardstyle", "Bass Boost"),
        )
        assertEquals("Hardstyle", ranked.first().headline.split(" ").getOrNull(1))
        assertTrue(ranked.first().reason.contains("142 BPM"))
    }

    @Test
    fun `rejections decay future suggestions`() {
        assertTrue(BrainRanking.acceptanceDecay(0) > BrainRanking.acceptanceDecay(3))
    }

    @Test
    fun `near duplicate finder pairs matching fingerprints`() {
        val a = sound(140, 1).copy(fingerprint = "abcd")
        val b = sound(140, 2).copy(fingerprint = "abce")
        val c = sound(140, 3).copy(fingerprint = "0000")
        fun hamming(x: String, y: String): Int {
            val a = x.toLongOrNull(16) ?: return 64
            val b = y.toLongOrNull(16) ?: return 64
            return java.lang.Long.bitCount(a xor b)
        }
        val pairs = BrainRanking.findNearDuplicates(listOf(a, b, c), ::hamming)
        assertEquals(listOf(1L to 2L), pairs)
    }

    @Test
    fun `next track picks the closest bpm and key`() {
        val current = sound(140, 1).copy(key = MusicalKey.C)
        val near = sound(142, 2).copy(key = MusicalKey.C)
        val far = sound(90, 3).copy(key = MusicalKey.A_SHARP)
        assertEquals(near.id, BrainRanking.suggestNext(current, listOf(near, far))?.id)
    }
}
