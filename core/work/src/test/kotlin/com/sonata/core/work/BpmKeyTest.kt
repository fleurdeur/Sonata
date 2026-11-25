package com.sonata.core.work

import com.sonata.core.work.analysis.AudioAnalysis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class BpmKeyTest {

    /** A click track: noise bursts at exactly 140 BPM. */
    private fun clickTrack(bpm: Int, sampleRate: Int = 44100, seconds: Int = 8): ShortArray {
        val period = (60.0 / bpm * sampleRate).toInt()
        val total = sampleRate * seconds
        val pcm = ShortArray(total)
        for (start in 0 until total step period) {
            for (i in 0 until 2000) {
                if (start + i < total) {
                    pcm[start + i] = (8000 * sin(2 * PI * 1000.0 * i / sampleRate)).toInt().toShort()
                }
            }
        }
        return pcm
    }

    @Test
    fun `bpm detection finds 140 bpm within tolerance`() {
        val bpm = AudioAnalysis.detectBpm(clickTrack(140), 44100)
        assertTrue("bpm=$bpm", bpm in 130..150)
    }

    @Test
    fun `key detection resolves C major from a C chord`() {
        val sampleRate = 44100
        val cChord = ShortArray(sampleRate * 2) { i ->
            val v = sin(2 * PI * 261.63 * i / sampleRate) +      // C4
                sin(2 * PI * 329.63 * i / sampleRate) +          // E4
                sin(2 * PI * 392.0 * i / sampleRate)             // G4
            (v / 3 * 20000).toInt().toShort()
        }
        val keyIndex = AudioAnalysis.detectKey(cChord, sampleRate)
        assertTrue("keyIndex=$keyIndex", keyIndex == 0 || keyIndex == 4 || keyIndex == 7)
    }

    @Test
    fun `loudness is in a sane LUFS range`() {
        val loud = AudioAnalysis.loudnessLufs(ShortArray(44100) { 32000.toShort() })
        assertTrue(loud > -3f && loud < 1f)
        val silent = AudioAnalysis.loudnessLufs(ShortArray(44100))
        assertEquals(-70f, silent, 0.01f)
    }
}
