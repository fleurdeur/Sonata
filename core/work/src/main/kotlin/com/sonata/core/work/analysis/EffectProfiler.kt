package com.sonata.core.work.analysis

import com.sonata.core.audio.render.AcousticMatcher
import com.sonata.core.audio.render.EffectSpec
import com.sonata.core.audio.render.PcmTools
import kotlin.math.abs
import kotlin.math.log2

/**
 * Recovers the edit that was applied to the shared clip. Speed and pitch are
 * measured against the full recording; reverb is only added when the clip's
 * tail is measurably longer than the same part of the clean song. Nothing is
 * inferred from a vague caption alone.
 */
object EffectProfiler {

    fun profile(
        clipKey: Int,
        clipPcm: ShortArray,
        fullPcm: ShortArray,
        tailRatio: Double,
        keywords: String,
        matched: AcousticMatcher.Match?,
    ): EffectSpec {
        val speed = matched?.speed?.takeIf { matched.verified } ?: 1f
        val sourceOffset = ((matched?.offsetMs ?: 0L).toFloat() * speed).coerceAtLeast(0f).toLong()
        val start = (sourceOffset - 15_000L).coerceAtLeast(0L)
        val end = (start + 60_000L).coerceAtMost(fullPcm.size.toLong() * 1000 / PcmTools.SAMPLE_RATE)
        val from = (start * PcmTools.SAMPLE_RATE / 1000).toInt().coerceIn(0, fullPcm.size)
        val to = (end * PcmTools.SAMPLE_RATE / 1000).toInt().coerceIn(from, fullPcm.size)
        val analysisWindow = if (to - from >= PcmTools.SAMPLE_RATE * 4) {
            fullPcm.copyOfRange(from, to)
        } else {
            fullPcm
        }
        val fullKey = AudioAnalysis.detectKey(analysisWindow, PcmTools.SAMPLE_RATE)
        val semitones = PcmTools.semitoneDelta(fullKey, clipKey)
        val expected = 12f * log2(speed)
        val saysNightcore = keywords.contains("nightcore")
        val measuredPitch = matched?.pitchSemitones ?: 0
        val pitchAgrees = abs(measuredPitch.toFloat() - expected) <= 1.5f && abs(measuredPitch) >= 1
        val saysSpedUp = keywords.contains("sped up") || keywords.contains("speed up")
        val coupled = when {
            speed == 1f -> false
            saysNightcore -> true
            pitchAgrees -> true
            saysSpedUp && speed > 1.08f -> true
            abs(semitones.toFloat() - expected) <= 1.2f && abs(semitones) >= 1 -> true
            else -> false
        }

        val fullTail = PcmTools.reverbTailRatio(analysisWindow)
        val extraTail = tailRatio - fullTail
        val saysReverb = keywords.contains("reverb")
        val reverb = when {
            extraTail >= 0.22 -> 0.26f
            extraTail >= 0.13 -> 0.16f
            saysReverb && extraTail >= 0.04 -> 0.14f
            else -> 0f
        }
        val bass = if (keywords.contains("bass boost") || keywords.contains("bass boosted")) {
            3f
        } else {
            0f
        }
        return EffectSpec(speed = speed, pitchCoupled = coupled, reverbMix = reverb, bassDb = bass)
    }
}
