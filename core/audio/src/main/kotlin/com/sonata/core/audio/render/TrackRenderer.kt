package com.sonata.core.audio.render

import kotlin.math.abs

/**
 * The audio character of a TikTok sound, measured from the shared video.
 *
 * [speed] > 1 is a sped-up edit, < 1 a slowed edit. [pitchCoupled] is true for
 * nightcore-style edits where the pitch moves with the tempo.
 */
data class EffectSpec(
    val speed: Float = 1f,
    val pitchCoupled: Boolean = false,
    val reverbMix: Float = 0f,
    val bassDb: Float = 0f,
) {
    val isNeutral: Boolean
        get() = abs(speed - 1f) < 0.01f && reverbMix < 0.01f && abs(bassDb) < 0.5f

    /** Human labels for the library and the import result. */
    fun labels(): List<String> = buildList {
        if (speed > 1.04f) add(if (pitchCoupled) "Nightcore" else "Sped up")
        if (speed < 0.96f) add("Slowed")
        if (reverbMix >= 0.15f) add("Reverb")
        if (bassDb >= 2f) add("Bass boosted")
    }
}

/**
 * Turns a clip (or a full song) into the final playable track: tempo, pitch,
 * bass, reverb, then loop/trim to the requested length and peak-normalize.
 */
object TrackRenderer {

    fun render(
        source: ShortArray,
        spec: EffectSpec,
        targetDurationMs: Long? = null,
        loopClip: Boolean = true,
        sampleRate: Int = PcmTools.SAMPLE_RATE,
        fadeInMs: Int = 120,
        fadeOutMs: Int = 1_200,
    ): ShortArray {
        if (source.isEmpty()) return source
        var pcm = source
        if (abs(spec.speed - 1f) >= 0.01f) {
            pcm = if (spec.pitchCoupled) {
                PcmTools.resample(pcm, spec.speed)
            } else {
                PcmTools.timeStretch(pcm, spec.speed, sampleRate)
            }
        }
        if (abs(spec.bassDb) >= 0.5f) pcm = PcmTools.bassBoost(pcm, spec.bassDb.toDouble(), sampleRate = sampleRate)
        if (spec.reverbMix >= 0.01f) pcm = PcmTools.reverb(pcm, spec.reverbMix, sampleRate)

        if (targetDurationMs != null && targetDurationMs > 0) {
            val targetSamples = (targetDurationMs * sampleRate / 1000).toInt()
            pcm = when {
                pcm.size > targetSamples && loopClip -> pcm.copyOfRange(0, targetSamples)
                pcm.size < targetSamples && loopClip -> PcmTools.loopToLength(pcm, targetDurationMs, sampleRate = sampleRate)
                else -> pcm
            }
        }
        pcm = PcmTools.fade(pcm, fadeInMs, fadeOutMs, sampleRate)
        return PcmTools.normalize(pcm)
    }
}
