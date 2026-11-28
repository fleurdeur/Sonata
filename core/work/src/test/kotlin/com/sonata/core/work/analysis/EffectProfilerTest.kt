package com.sonata.core.work.analysis

import com.sonata.core.audio.render.AcousticMatcher
import com.sonata.core.audio.render.PcmTools
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectProfilerTest {

    @Test
    fun chosenRecordingKeepsMeasuredSpeedWhenUnverified() {
        val clip = tone(220.0, seconds = 6)
        val full = tone(220.0, seconds = 10)
        val match = AcousticMatcher.Match(
            verified = false,
            speed = 1.25f,
            offsetMs = 9_000L,
            score = 0.42f,
            pitchSemitones = 4,
        )
        val forced = EffectProfiler.profile(
            clipKey = AudioAnalysis.detectKey(clip, PcmTools.SAMPLE_RATE),
            clipPcm = clip,
            fullPcm = full,
            tailRatio = 0.1,
            keywords = "",
            matched = match,
            allowUnverified = true,
        )
        assertEquals(1.25f, forced.speed, 0.001f)
        assertTrue(forced.pitchCoupled)

        val strict = EffectProfiler.profile(
            clipKey = AudioAnalysis.detectKey(clip, PcmTools.SAMPLE_RATE),
            clipPcm = clip,
            fullPcm = full,
            tailRatio = 0.1,
            keywords = "",
            matched = match,
            allowUnverified = false,
        )
        assertEquals(1f, strict.speed, 0.001f)
    }

    @Test
    fun measuredLowEndAddsBassWithoutCaption() {
        val clip = tone(70.0, seconds = 6)
        val full = tone(900.0, seconds = 10)
        val effect = EffectProfiler.profile(
            clipKey = 0,
            clipPcm = clip,
            fullPcm = full,
            tailRatio = 0.1,
            keywords = "",
            matched = null,
        )
        assertEquals(3f, effect.bassDb, 0.001f)
    }

    private fun tone(hz: Double, seconds: Int): ShortArray {
        val samples = ShortArray(seconds * PcmTools.SAMPLE_RATE)
        for (index in samples.indices) {
            samples[index] = (sin(2.0 * PI * hz * index / PcmTools.SAMPLE_RATE) * 20_000).toInt().toShort()
        }
        return samples
    }
}
