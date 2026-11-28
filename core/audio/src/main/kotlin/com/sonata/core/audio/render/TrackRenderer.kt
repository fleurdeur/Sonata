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
        onProgress: ((Float) -> Unit)? = null,
    ): ShortArray {
        if (source.isEmpty()) return source
        var pcm = source
        var owned = false
        onProgress?.invoke(0.02f)
        if (abs(spec.speed - 1f) >= 0.01f) {
            val speedProgress: (Float) -> Unit = { fraction ->
                onProgress?.invoke(0.05f + fraction.coerceIn(0f, 1f) * 0.55f)
            }
            pcm = if (spec.pitchCoupled) {
                PcmTools.resample(pcm, spec.speed, speedProgress)
            } else {
                PcmTools.timeStretch(pcm, spec.speed, sampleRate, speedProgress)
            }
            owned = pcm !== source
        }
        onProgress?.invoke(0.62f)
        if (abs(spec.bassDb) >= 0.5f) {
            pcm = PcmTools.bassBoost(pcm, spec.bassDb.toDouble(), sampleRate = sampleRate)
            owned = true
        }
        onProgress?.invoke(0.68f)
        if (spec.reverbMix >= 0.01f) {
            pcm = PcmTools.reverb(
                pcm,
                spec.reverbMix,
                sampleRate,
                onProgress = { fraction -> onProgress?.invoke(0.68f + fraction.coerceIn(0f, 1f) * 0.24f) },
            )
            owned = true
        }

        if (targetDurationMs != null && targetDurationMs > 0) {
            val targetSamples = (targetDurationMs * sampleRate / 1000).toInt()
            pcm = when {
                pcm.size > targetSamples && loopClip -> pcm.copyOfRange(0, targetSamples)
                pcm.size < targetSamples && loopClip -> PcmTools.loopToLength(pcm, targetDurationMs, sampleRate = sampleRate)
                else -> pcm
            }
            owned = true
        }
        // An effect-free render still owns its output before the in-place
        // fade/normalize pass, so the caller's PCM is never mutated.
        if (!owned) pcm = source.copyOf()
        pcm = PcmTools.fadeInPlace(pcm, fadeInMs, fadeOutMs, sampleRate)
        onProgress?.invoke(0.96f)
        pcm = PcmTools.normalizeInPlace(pcm)
        onProgress?.invoke(1f)
        return pcm
    }
}
