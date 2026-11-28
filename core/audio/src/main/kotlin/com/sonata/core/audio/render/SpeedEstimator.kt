package com.sonata.core.audio.render

/**
 * Backwards-compatible facade over [AcousticMatcher].
 *
 * Speed is never inferred from a beat counter and is never applied on weak
 * evidence. A clip that cannot be proven against the full recording returns
 * 1.0x so the app keeps the real audio instead of mangling it.
 */
object SpeedEstimator {

    data class Estimate(
        val speed: Float,
        val confidence: Double,
        /** The long sound is the same version the clip uses, not an original. */
        val sameRecording: Boolean,
        /** Start of the clip inside the time-scaled long sound. */
        val offsetMs: Long = 0,
    )

    fun estimate(
        clip: ShortArray,
        full: ShortArray,
        sampleRate: Int = PcmTools.SAMPLE_RATE,
    ): Estimate = AcousticMatcher.estimate(clip, full, sampleRate)
}