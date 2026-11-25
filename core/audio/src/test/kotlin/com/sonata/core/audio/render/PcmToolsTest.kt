package com.sonata.core.audio.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class PcmToolsTest {

    private val sr = PcmTools.SAMPLE_RATE

    private fun sine(seconds: Double, hz: Double, amplitude: Double = 0.6): ShortArray {
        val n = (seconds * sr).toInt()
        return ShortArray(n) { i ->
            (sin(2 * PI * hz * i / sr) * amplitude * 32767).toInt().toShort()
        }
    }

    /** Zero-crossing estimate of the fundamental frequency. */
    private fun zeroCrossHz(pcm: ShortArray): Double {
        var crossings = 0
        for (i in 1 until pcm.size) {
            if ((pcm[i - 1] < 0 && pcm[i] >= 0) || (pcm[i - 1] >= 0 && pcm[i] < 0)) crossings++
        }
        return crossings.toDouble() * sr / (2.0 * pcm.size)
    }

    @Test
    fun `loop reaches the requested length exactly`() {
        val clip = sine(1.0, 440.0)
        val looped = PcmTools.loopToLength(clip, targetMs = 3_500, crossfadeMs = 40)
        assertEquals((3.5 * sr).toInt(), looped.size)
        assertTrue(PcmTools.peak(looped) > 10_000)
    }

    @Test
    fun `loop seam stays inside the signal envelope`() {
        val clip = sine(1.0, 220.0)
        val looped = PcmTools.loopToLength(clip, targetMs = 2_000, crossfadeMs = 40)
        // A click would be a near-full-scale jump between adjacent samples.
        for (i in 1 until looped.size) {
            val jump = abs(looped[i].toInt() - looped[i - 1].toInt())
            assertTrue("click at $i: $jump", jump < 12_000)
        }
    }

    @Test
    fun `time stretch changes duration but keeps pitch`() {
        val tone = sine(2.0, 440.0)
        val faster = PcmTools.timeStretch(tone, 1.25f)
        assertEquals(tone.size / 1.25, faster.size.toDouble(), 2_000.0)
        assertEquals(440.0, zeroCrossHz(faster), 25.0)
    }

    @Test
    fun `resampling shifts pitch with tempo`() {
        val tone = sine(2.0, 440.0)
        val nightcore = PcmTools.resample(tone, 1.25f)
        assertEquals(440.0 * 1.25, zeroCrossHz(nightcore), 30.0)
    }

    @Test
    fun `reverb adds a tail after an impulse`() {
        val impulse = ShortArray(sr) { 0 }
        impulse[0] = 20_000
        val wet = PcmTools.reverb(impulse, mix = 0.5f)
        val tail = PcmTools.rms(wet, sr / 4, sr / 2)
        assertTrue("tail energy should be audible", tail > 0.0001)
    }

    @Test
    fun `reverb tail ratio separates dry and washed clips`() {
        val dry = ShortArray(sr) { i -> if (i % (sr / 4) == 0) 20_000.toShort() else 0 }
        val wet = PcmTools.reverb(dry, mix = 0.8f)
        assertTrue(PcmTools.reverbTailRatio(wet) > PcmTools.reverbTailRatio(dry))
    }

    @Test
    fun `bass boost raises low frequency energy without clipping`() {
        val low = sine(1.0, 60.0, amplitude = 0.4)
        val boosted = PcmTools.bassBoost(low, 6.0)
        assertTrue(PcmTools.rms(boosted) > PcmTools.rms(low))
        assertTrue(PcmTools.peak(boosted) <= 32767)
    }

    @Test
    fun `render neutral is peak normalized and keeps duration`() {
        val clip = sine(2.0, 330.0, amplitude = 0.2)
        val out = TrackRenderer.render(clip, EffectSpec(), targetDurationMs = null, loopClip = true)
        assertEquals(clip.size, out.size)
        assertTrue(PcmTools.peak(out) > PcmTools.peak(clip))
    }

    @Test
    fun `render loops a short clip up to the target duration`() {
        val clip = sine(2.0, 330.0)
        val out = TrackRenderer.render(clip, EffectSpec(), targetDurationMs = 15_000)
        assertEquals((15.0 * sr).toInt(), out.size)
    }

    @Test
    fun `render nightcore is shorter than the source`() {
        val clip = sine(4.0, 440.0)
        val out = TrackRenderer.render(clip, EffectSpec(speed = 1.25f, pitchCoupled = true))
        assertEquals(clip.size / 1.25, out.size.toDouble(), 2_000.0)
    }

    @Test
    fun `semitone delta folds into the shortest interval`() {
        assertEquals(0, PcmTools.semitoneDelta(0, 0))
        assertEquals(2, PcmTools.semitoneDelta(0, 2))
        assertEquals(-2, PcmTools.semitoneDelta(0, 10))
    }

    @Test
    fun `effect labels describe nightcore and reverb`() {
        val labels = EffectSpec(speed = 1.25f, pitchCoupled = true, reverbMix = 0.4f).labels()
        assertTrue(labels.contains("Nightcore"))
        assertTrue(labels.contains("Reverb"))
    }
}
