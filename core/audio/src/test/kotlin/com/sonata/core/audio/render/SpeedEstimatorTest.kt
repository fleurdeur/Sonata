package com.sonata.core.audio.render

import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedEstimatorTest {

    private val sr = PcmTools.SAMPLE_RATE

    /** A simple drum-and-chord arrangement with sharp, repeating onsets. */
    private fun song(seconds: Double): ShortArray {
        val n = (seconds * sr).toInt()
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val v = 0.16 * sin(2 * PI * 110.0 * t) + 0.09 * sin(2 * PI * 220.0 * t)
            out[i] = (v * 32767).toInt().toShort()
        }
        val random = Random(11)
        var beat = 0
        while (beat * sr / 2 < n) {
            val start = beat * sr / 2
            val length = (0.12 * sr).toInt()
            for (i in 0 until length) {
                val idx = start + i
                if (idx >= n) break
                val t = i.toDouble() / sr
                val env = exp(-t * 28.0)
                val v = sin(2 * PI * 55.0 * t) * 0.8 * env
                out[idx] = (out[idx] + v * 32767).toInt().coerceIn(-32768, 32767).toShort()
            }
            beat++
        }
        var hat = 0
        while (hat * sr / 4 < n) {
            val start = hat * sr / 4
            val length = (0.03 * sr).toInt()
            for (i in 0 until length) {
                val idx = start + i
                if (idx >= n) break
                val env = exp(-i.toDouble() / (0.008 * sr))
                val v = (random.nextDouble() * 2 - 1) * 0.25 * env
                out[idx] = (out[idx] + v * 32767).toInt().coerceIn(-32768, 32767).toShort()
            }
            hat++
        }
        return out
    }

    private fun excerpt(full: ShortArray, startSeconds: Double, seconds: Double): ShortArray {
        val from = (startSeconds * sr).toInt()
        val to = (from + seconds * sr).toInt().coerceAtMost(full.size)
        return full.copyOfRange(from, to)
    }

    @Test
    fun detects_a_sped_up_clip() {
        val full = song(24.0)
        val clip = PcmTools.resample(excerpt(full, 4.0, 10.0), 1.25f)
        val estimate = SpeedEstimator.estimate(clip, full, sr)
        assertEquals(1.25, estimate.speed.toDouble(), 0.04)
        assertTrue("confidence " + estimate.confidence, estimate.confidence > 0.4)
    }

    @Test
    fun detects_a_slowed_clip() {
        val full = song(24.0)
        val clip = PcmTools.resample(excerpt(full, 5.0, 7.0), 0.8f)
        val estimate = SpeedEstimator.estimate(clip, full, sr)
        assertEquals(0.8, estimate.speed.toDouble(), 0.05)
    }

    @Test
    fun unrelated_audio_is_not_slowed() {
        val full = song(24.0)
        val other = ShortArray((8.0 * sr).toInt()) { i ->
            val t = i.toDouble() / sr
            ((0.3 * sin(2 * PI * 310.0 * t) + 0.2 * sin(2 * PI * 470.0 * t)) * 32767).toInt().toShort()
        }
        val estimate = SpeedEstimator.estimate(other, full, sr)
        assertEquals(1.0, estimate.speed.toDouble(), 0.001)
    }

    @Test
    fun unchanged_excerpt_stays_at_normal_speed() {
        val full = song(24.0)
        val clip = excerpt(full, 8.0, 8.0)
        val estimate = SpeedEstimator.estimate(clip, full, sr)
        assertEquals(1.0, estimate.speed.toDouble(), 0.001)
        assertTrue(estimate.sameRecording)
    }
}