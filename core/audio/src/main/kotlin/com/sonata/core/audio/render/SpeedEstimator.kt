package com.sonata.core.audio.render

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Recovers the playback speed of a shared clip relative to a longer sound.
 *
 * Tempo counters are unreliable on 15-second clips: they routinely report half
 * or double the real tempo, and applying that ratio to a full song turns a
 * sped-up edit into something that crawls. This estimator does not count beats.
 * It builds an onset envelope for both signals, compresses the full song by each
 * candidate speed and keeps the speed whose envelope best matches the clip over
 * several seconds. If nothing matches convincingly it returns 1.0, so a guess
 * can never mangle the audio.
 */
object SpeedEstimator {

    data class Estimate(
        val speed: Float,
        val confidence: Double,
        /** The long sound is the same version the clip uses, not an original. */
        val sameRecording: Boolean,
    )

    private const val ENVELOPE_HZ = 50
    private const val MIN_CLIP_SECONDS = 3
    private const val MATCH_SECONDS = 10

    private val candidates: FloatArray = buildList {
        var s = 0.75f
        while (s <= 1.5f + 1e-4f) {
            add((s * 1000f).toInt() / 1000f)
            s += 0.025f
        }
        if (none { abs(it - 1f) < 1e-4f }) add(1f)
    }.toFloatArray()

    fun estimate(
        clip: ShortArray,
        full: ShortArray,
        sampleRate: Int = PcmTools.SAMPLE_RATE,
    ): Estimate {
        if (clip.size < sampleRate * MIN_CLIP_SECONDS || full.size < sampleRate * 5) {
            return Estimate(1f, 0.0, false)
        }
        val clipEnvFull = envelope(clip, sampleRate)
        val fullEnv = envelope(full, sampleRate)
        if (fullEnv.size < ENVELOPE_HZ * 4) return Estimate(1f, 0.0, false)
        val clipEnv = clipEnvFull.let {
            if (it.size > ENVELOPE_HZ * MATCH_SECONDS) it.copyOf(ENVELOPE_HZ * MATCH_SECONDS) else it
        }
        if (clipEnv.size < ENVELOPE_HZ * MIN_CLIP_SECONDS) return Estimate(1f, 0.0, false)

        var bestSpeed = 1f
        var bestScore = -1.0
        var scoreAtOne = 0.0
        for (speed in candidates) {
            val scaled = stretchEnvelope(fullEnv, speed)
            if (scaled.size < clipEnv.size + 2) continue
            val score = bestCorrelation(clipEnv, scaled)
            if (speed == 1f) scoreAtOne = score
            if (score > bestScore) {
                bestScore = score
                bestSpeed = speed
            }
        }
        if (bestScore < 0.30) return Estimate(1f, bestScore.coerceAtLeast(0.0), false)

        val same = abs(bestSpeed - 1f) <= 0.02f && bestScore >= 0.45
        var speed = if (same) 1f else bestSpeed
        if (speed != 1f) {
            val margin = bestScore - scoreAtOne
            // Slowing is only accepted on very strong evidence: a wrong slow
            // guess is far more damaging than leaving the song at its real tempo.
            val accepted = if (speed > 1f) {
                bestScore >= 0.42 && margin >= 0.04
            } else {
                bestScore >= 0.55 && margin >= 0.10
            }
            if (!accepted) speed = 1f
        }
        if (abs(speed - 1f) < 0.02f) speed = 1f
        return Estimate(speed, bestScore, same && speed == 1f)
    }

    /** Onset strength: positive changes in short-term RMS, which survive pitch shifts. */
    private fun envelope(pcm: ShortArray, sampleRate: Int): DoubleArray {
        val hop = sampleRate / ENVELOPE_HZ
        val frames = pcm.size / hop
        if (frames < 4) return DoubleArray(0)
        val energy = DoubleArray(frames)
        for (f in 0 until frames) {
            var sum = 0.0
            val start = f * hop
            for (i in 0 until hop) {
                val v = pcm[start + i] / 32768.0
                sum += v * v
            }
            energy[f] = sqrt(sum / hop)
        }
        val env = DoubleArray(frames)
        for (i in 1 until frames) env[i] = max(0.0, energy[i] - energy[i - 1])
        return env
    }

    /** Resamples the envelope in time by [speed] (speed > 1 compresses it). */
    private fun stretchEnvelope(env: DoubleArray, speed: Float): DoubleArray {
        if (env.isEmpty()) return env
        val length = ((env.size - 1) / speed).toInt() + 1
        val out = DoubleArray(length)
        for (i in 0 until length) {
            val src = i * speed
            val i0 = src.toInt().coerceIn(0, env.size - 1)
            val i1 = (i0 + 1).coerceAtMost(env.size - 1)
            val frac = src - i0
            out[i] = env[i0] + (env[i1] - env[i0]) * frac
        }
        return out
    }

    /** Highest Pearson correlation of [clip] anywhere in [track]. */
    private fun bestCorrelation(clip: DoubleArray, track: DoubleArray): Double {
        val n = clip.size
        if (n < 8 || track.size < n) return 0.0
        var clipMean = 0.0
        for (v in clip) clipMean += v
        clipMean /= n
        val centered = DoubleArray(n)
        var clipVar = 0.0
        for (i in 0 until n) {
            val c = clip[i] - clipMean
            centered[i] = c
            clipVar += c * c
        }
        if (clipVar <= 1e-12) return 0.0

        val sum = DoubleArray(track.size + 1)
        val sumSq = DoubleArray(track.size + 1)
        for (i in track.indices) {
            sum[i + 1] = sum[i] + track[i]
            sumSq[i + 1] = sumSq[i] + track[i] * track[i]
        }

        var best = -1.0
        var bestOffset = 0
        val coarse = 4
        var offset = 0
        while (offset + n <= track.size) {
            val score = pearson(centered, clipVar, track, sum, sumSq, offset)
            if (score > best) { best = score; bestOffset = offset }
            offset += coarse
        }
        var refine = max(0, bestOffset - coarse)
        val limit = (bestOffset + coarse).coerceAtMost(track.size - n)
        while (refine <= limit) {
            val score = pearson(centered, clipVar, track, sum, sumSq, refine)
            if (score > best) best = score
            refine++
        }
        return best
    }

    private fun pearson(
        centered: DoubleArray,
        clipVar: Double,
        track: DoubleArray,
        sum: DoubleArray,
        sumSq: DoubleArray,
        offset: Int,
    ): Double {
        val n = centered.size
        val trackMean = (sum[offset + n] - sum[offset]) / n
        val trackVar = (sumSq[offset + n] - sumSq[offset]) / n - trackMean * trackMean
        if (trackVar <= 1e-12) return 0.0
        var cross = 0.0
        for (i in 0 until n) {
            cross += centered[i] * (track[offset + i] - trackMean)
        }
        return cross / sqrt(clipVar * trackVar * n)
    }
}