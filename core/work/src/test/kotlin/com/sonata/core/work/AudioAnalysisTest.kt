package com.sonata.core.work

import com.sonata.core.work.analysis.AudioAnalysis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AudioAnalysisTest {

    private fun sine(freq: Int, sampleRate: Int, seconds: Double): ShortArray {
        val n = (sampleRate * seconds).toInt()
        return ShortArray(n) { (32767.0 * sin(2 * PI * freq * it / sampleRate)).toInt().toShort() }
    }

    @Test
    fun `silence detection trims dead air on both ends`() {
        val sampleRate = 44100
        val lead = ShortArray(sampleRate / 2) // 0.5 s of silence
        val body = sine(440, sampleRate, 1.0)
        val tail = ShortArray(sampleRate / 2)
        val pcm = lead + body + tail
        val (start, end) = AudioAnalysis.detectSilence(pcm, sampleRate)
        assertTrue("start=$start", start in 350..650)
        assertTrue("end=$end", end in 1000..1500)
    }

    @Test
    fun `identical audio fingerprints identically`() {
        val pcm = sine(440, 44100, 1.0)
        assertEquals(AudioAnalysis.fingerprint(pcm, 44100), AudioAnalysis.fingerprint(pcm, 44100))
    }

    private fun clickTrack(bpm: Int, sampleRate: Int = 44100, seconds: Int = 6): ShortArray {
        val period = (60.0 / bpm * sampleRate).toInt()
        val pcm = ShortArray(sampleRate * seconds)
        for (start in 0 until pcm.size step period) {
            for (i in 0 until 1500) {
                if (start + i < pcm.size) {
                    pcm[start + i] = (10000 * sin(2 * PI * 800.0 * i / sampleRate)).toInt().toShort()
                }
            }
        }
        return pcm
    }

    @Test
    fun `duplicates are detected within tolerance`() {
        val a = AudioAnalysis.fingerprint(clickTrack(140), 44100)
        val b = AudioAnalysis.fingerprint(clickTrack(140), 44100)
        assertTrue("a=$a b=$b", AudioAnalysis.isDuplicate(a, b, tolerance = 20))
    }

    @Test
    fun `different tracks are not duplicates`() {
        val a = AudioAnalysis.fingerprint(clickTrack(140), 44100)
        val b = AudioAnalysis.fingerprint(clickTrack(90), 44100)
        assertTrue(!AudioAnalysis.isDuplicate(a, b, tolerance = 8))
    }

    @Test
    fun `waveform peaks are normalized`() {
        val wave = AudioAnalysis.waveform(sine(440, 44100, 2.0), buckets = 32)
        assertEquals(32, wave.size)
        assertTrue(wave.all { it in 0f..1f })
        assertTrue(wave.any { it > 0.9f })
    }

    @Test
    fun `zero crossing snapping stays near the request`() {
        val pcm = sine(1000, 44100, 0.1)
        val snapped = AudioAnalysis.nextZeroCrossing(pcm, 1000, 44100)
        assertTrue(snapped >= 1000 && snapped < 1100)
    }
}
