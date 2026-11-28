package com.sonata.core.audio.render

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Audio-proof matching for short clips against full recordings.
 *
 * The old matcher correlated a 50 Hz loudness curve. That curve is nearly
 * identical for any four-on-the-floor pop song and the best of tens of
 * thousands of offsets will eventually cross any low threshold. This matcher
 * uses two independent signals:
 *
 *  - a log-mel spectral-flux sequence, which follows articulation and ignores
 *    absolute pitch, and
 *  - a 12-bin chroma sequence compared at all 12 pitch rotations.
 *
 * It also requires the winning speed to stand clear of every other speed, so a
 * flat or repetitive song cannot win by brute force. Work is done at 11.025 kHz.
 */
object AcousticMatcher {

    data class Match(
        val verified: Boolean,
        val speed: Float = 1f,
        val offsetMs: Long = 0,
        val score: Float = 0f,
        val chromaScore: Float = 0f,
        val margin: Float = 0f,
        val pitchSemitones: Int = 0,
        val sameRecording: Boolean = false,
    )

    private const val RATE = 11_025
    private const val FFT_SIZE = 2_048
    private const val HOP = 512
    private const val FRAME_HZ = RATE.toDouble() / HOP
    private const val MIN_CLIP_FRAMES = 24

    // 0.70x..1.60x in 2% steps. Wider than TikTok's common 0.75..1.5 range
    // without making the search so fine that noise can win.
    private val speeds: FloatArray = buildList {
        var s = 0.70f
        while (s <= 1.60f + 1e-4f) {
            add((s * 100f).roundToInt() / 100f)
            s += 0.02f
        }
        if (none { abs(it - 1f) < 1e-4f }) add(1f)
    }.toFloatArray()

    private val hann = DoubleArray(FFT_SIZE) { i ->
        0.5 - 0.5 * cos(2.0 * PI * i / (FFT_SIZE - 1))
    }

    private val melFilters: Array<DoubleArray> = buildMelFilters()
    private val chromaBin: IntArray = IntArray(FFT_SIZE / 2) { bin ->
        val hz = bin.toDouble() * RATE / FFT_SIZE
        if (hz < 55.0 || hz > 2_000.0) -1 else {
            val midi = 69.0 + 12.0 * log2(hz / 440.0)
            ((midi.roundToInt() % 12) + 12) % 12
        }
    }

    fun match(
        clip: ShortArray,
        full: ShortArray,
        sampleRate: Int = PcmTools.SAMPLE_RATE,
    ): Match {
        if (clip.size < sampleRate * 3 || full.size < sampleRate * 5) {
            return Match(verified = false)
        }
        val clipFeatures = features(clip, sampleRate)
        val fullFeatures = features(full, sampleRate)
        if (clipFeatures.flux.size < MIN_CLIP_FRAMES ||
            fullFeatures.flux.size < clipFeatures.flux.size + 4
        ) {
            return Match(verified = false)
        }

        var bestScore = -1.0
        var bestSpeed = 1f
        var bestOffset = 0
        var secondScore = -1.0
        val speedBest = ArrayList<Pair<Float, Double>>(speeds.size)
        for (speed in speeds) {
            val scaled = resample(fullFeatures.flux, speed)
            if (scaled.size < clipFeatures.flux.size + 4) continue
            val (score, offset) = bestCorrelation(clipFeatures.flux, scaled)
            speedBest += speed to score
            if (score > bestScore) {
                bestScore = score
                bestSpeed = speed
                bestOffset = offset
            }
        }
        if (bestScore < 0.38) return Match(verified = false, score = bestScore.toFloat())

        for ((speed, score) in speedBest) {
            if (abs(speed - bestSpeed) >= 0.05f && score > secondScore) secondScore = score
        }
        val margin = if (secondScore < 0.0) bestScore else (bestScore - secondScore)

        val scaledChroma = Array(12) { resample(fullFeatures.chroma[it], bestSpeed) }
        var bestChroma = -1.0
        var bestRotation = 0
        for (rotation in 0 until 12) {
            val value = chromaSimilarity(
                clipFeatures.chroma,
                scaledChroma,
                bestOffset,
                rotation,
            )
            if (value > bestChroma) {
                bestChroma = value
                bestRotation = rotation
            }
        }

        // A pitch rotation is only meaningful when it is clearly better than
        // the untransposed comparison. Otherwise the chroma is just ambiguous.
        val plainChroma = chromaSimilarity(clipFeatures.chroma, scaledChroma, bestOffset, 0)
        val pitch = if (bestRotation != 0 && bestChroma - plainChroma >= 0.04) {
            if (bestRotation <= 6) bestRotation else bestRotation - 12
        } else {
            0
        }
        val chroma = bestChroma.coerceAtLeast(0.0)
        val score = (0.55 * bestScore + 0.35 * chroma + 0.10 * margin.coerceIn(0.0, 0.3) / 0.3)
        val verified = bestScore >= 0.48 && chroma >= 0.55 && margin >= 0.06
        var speed = bestSpeed
        if (abs(speed - 1f) <= 0.015f) speed = 1f
        val offsetMs = (bestOffset * 1_000.0 / FRAME_HZ).roundToLong()
        return Match(
            verified = verified,
            speed = speed,
            offsetMs = offsetMs,
            score = score.coerceIn(0.0, 1.0).toFloat(),
            chromaScore = chroma.toFloat(),
            margin = margin.toFloat(),
            pitchSemitones = pitch,
            sameRecording = abs(speed - 1f) <= 0.02f && chroma >= 0.68,
        )
    }

    /** Compatibility entry point for callers that only need an estimate. */
    fun estimate(
        clip: ShortArray,
        full: ShortArray,
        sampleRate: Int = PcmTools.SAMPLE_RATE,
    ): SpeedEstimator.Estimate {
        val match = match(clip, full, sampleRate)
        return SpeedEstimator.Estimate(
            speed = if (match.verified) match.speed else 1f,
            confidence = if (match.verified) match.score.toDouble() else 0.0,
            sameRecording = match.verified && match.sameRecording,
            offsetMs = if (match.verified) match.offsetMs else 0,
        )
    }

    private class Features(val flux: FloatArray, val chroma: Array<FloatArray>)

    private fun features(pcm: ShortArray, sampleRate: Int): Features {
        val mono = toAnalysisRate(pcm, sampleRate)
        val frames = if (mono.size < FFT_SIZE) 0 else (mono.size - FFT_SIZE) / HOP + 1
        if (frames <= 0) return Features(FloatArray(0), Array(12) { FloatArray(0) })
        val flux = FloatArray(frames)
        val chroma = Array(12) { FloatArray(frames) }
        val window = DoubleArray(FFT_SIZE)
        val re = DoubleArray(FFT_SIZE)
        val im = DoubleArray(FFT_SIZE)
        val power = DoubleArray(FFT_SIZE / 2)
        val mel = DoubleArray(melFilters.size)
        var previous: DoubleArray? = null
        for (frame in 0 until frames) {
            val start = frame * HOP
            var rms = 0.0
            for (i in 0 until FFT_SIZE) {
                val v = mono[start + i] / 32768.0
                rms += v * v
                window[i] = v * hann[i]
                re[i] = window[i]
                im[i] = 0.0
            }
            fft(re, im)
            var frameEnergy = 0.0
            for (bin in 1 until FFT_SIZE / 2) {
                val p = re[bin] * re[bin] + im[bin] * im[bin]
                power[bin] = p
                frameEnergy += p
                val pc = chromaBin[bin]
                if (pc >= 0) chroma[pc][frame] += sqrt(p).toFloat()
            }
            if (frameEnergy <= 1e-9 || rms / FFT_SIZE < 1e-8) {
                flux[frame] = 0f
                previous = null
                continue
            }
            for (b in melFilters.indices) {
                var energy = 0.0
                val filter = melFilters[b]
                for (bin in 1 until FFT_SIZE / 2) energy += filter[bin] * power[bin]
                mel[b] = ln(1.0 + energy)
            }
            previous?.let { prev ->
                var sum = 0.0
                for (b in mel.indices) {
                    val d = mel[b] - prev[b]
                    if (d > 0.0) sum += d * d
                }
                flux[frame] = sqrt(sum).toFloat()
            }
            previous = mel.copyOf()
            var norm = 0.0
            for (p in 0 until 12) norm += chroma[p][frame] * chroma[p][frame]
            if (norm > 1e-12) {
                val inv = (1.0 / sqrt(norm)).toFloat()
                for (p in 0 until 12) chroma[p][frame] *= inv
            }
        }
        return Features(flux, chroma)
    }

    private fun toAnalysisRate(pcm: ShortArray, sampleRate: Int): ShortArray {
        if (sampleRate == RATE) return pcm
        val ratio = sampleRate.toDouble() / RATE
        if (abs(ratio - ratio.roundToInt().toDouble()) < 1e-6 && ratio >= 2.0) {
            val factor = ratio.roundToInt()
            val out = ShortArray(pcm.size / factor)
            for (i in out.indices) {
                var sum = 0
                val base = i * factor
                for (j in 0 until factor) sum += pcm[base + j]
                out[i] = (sum / factor).toShort()
            }
            return out
        }
        val outSize = (pcm.size / ratio).toInt().coerceAtLeast(0)
        val out = ShortArray(outSize)
        for (i in out.indices) {
            val src = i * ratio
            val i0 = src.toInt().coerceIn(0, pcm.size - 1)
            val i1 = (i0 + 1).coerceAtMost(pcm.size - 1)
            val f = src - i0
            out[i] = (pcm[i0] + (pcm[i1] - pcm[i0]) * f).toInt().toShort()
        }
        return out
    }

    private fun resample(input: FloatArray, speed: Float): FloatArray {
        if (input.isEmpty()) return input
        val length = ((input.size - 1) / speed).toInt() + 1
        val out = FloatArray(length)
        for (i in 0 until length) {
            val src = i * speed
            val i0 = src.toInt().coerceIn(0, input.size - 1)
            val i1 = (i0 + 1).coerceAtMost(input.size - 1)
            val f = src - i0
            out[i] = input[i0] + (input[i1] - input[i0]) * f
        }
        return out
    }

    private fun bestCorrelation(clip: FloatArray, track: FloatArray): Pair<Double, Int> {
        val n = clip.size
        if (n < 8 || track.size < n) return 0.0 to 0
        var mean = 0.0
        for (v in clip) mean += v
        mean /= n
        val centered = DoubleArray(n)
        var variance = 0.0
        for (i in 0 until n) {
            val c = clip[i] - mean
            centered[i] = c
            variance += c * c
        }
        if (variance <= 1e-12) return 0.0 to 0
        val sum = DoubleArray(track.size + 1)
        val sumSq = DoubleArray(track.size + 1)
        for (i in track.indices) {
            val v = track[i].toDouble()
            sum[i + 1] = sum[i] + v
            sumSq[i + 1] = sumSq[i] + v * v
        }
        var best = -1.0
        var bestOffset = 0
        var offset = 0
        while (offset + n <= track.size) {
            val score = pearson(centered, variance, track, sum, sumSq, offset)
            if (score > best) {
                best = score
                bestOffset = offset
            }
            offset += 2
        }
        var refine = max(0, bestOffset - 2)
        val limit = (bestOffset + 2).coerceAtMost(track.size - n)
        while (refine <= limit) {
            val score = pearson(centered, variance, track, sum, sumSq, refine)
            if (score > best) {
                best = score
                bestOffset = refine
            }
            refine++
        }
        return best to bestOffset
    }

    private fun pearson(
        centered: DoubleArray,
        variance: Double,
        track: FloatArray,
        sum: DoubleArray,
        sumSq: DoubleArray,
        offset: Int,
    ): Double {
        val n = centered.size
        val mean = (sum[offset + n] - sum[offset]) / n
        val trackVar = (sumSq[offset + n] - sumSq[offset]) / n - mean * mean
        if (trackVar <= 1e-12) return 0.0
        var cross = 0.0
        for (i in 0 until n) cross += centered[i] * (track[offset + i] - mean)
        return cross / sqrt(variance * trackVar * n)
    }

    private fun chromaSimilarity(
        clip: Array<FloatArray>,
        full: Array<FloatArray>,
        offset: Int,
        rotation: Int,
    ): Double {
        val frames = minOf(clip[0].size, full[0].size - offset)
        if (frames < 8) return 0.0
        var dot = 0.0
        var aNorm = 0.0
        var bNorm = 0.0
        for (t in 0 until frames) {
            for (p in 0 until 12) {
                val a = clip[p][t].toDouble()
                val b = full[(p + rotation) % 12][offset + t].toDouble()
                dot += a * b
                aNorm += a * a
                bNorm += b * b
            }
        }
        if (aNorm <= 1e-12 || bNorm <= 1e-12) return 0.0
        return dot / sqrt(aNorm * bNorm)
    }

    private fun buildMelFilters(): Array<DoubleArray> {
        val count = 24
        val minMel = hzToMel(80.0)
        val maxMel = hzToMel(2_000.0)
        val points = DoubleArray(count + 2) { i ->
            melToHz(minMel + (maxMel - minMel) * i / (count + 1))
        }
        return Array(count) { band ->
            val filter = DoubleArray(FFT_SIZE / 2)
            val left = points[band]
            val center = points[band + 1]
            val right = points[band + 2]
            for (bin in 1 until FFT_SIZE / 2) {
                val hz = bin.toDouble() * RATE / FFT_SIZE
                filter[bin] = when {
                    hz <= left || hz >= right -> 0.0
                    hz <= center -> (hz - left) / (center - left)
                    else -> (right - hz) / (right - center)
                }
            }
            filter
        }
    }

    private fun hzToMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)
    private fun melToHz(mel: Double): Double = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)

    /** In-place iterative radix-2 FFT; [re] and [im] must have a power-of-two size. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2.0 * PI / len
            val wLenCos = cos(angle)
            val wLenSin = sin(angle)
            var i = 0
            while (i < n) {
                var wr = 1.0
                var wi = 0.0
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + len / 2] * wr - im[i + k + len / 2] * wi
                    val vIm = re[i + k + len / 2] * wi + im[i + k + len / 2] * wr
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + len / 2] = uRe - vRe
                    im[i + k + len / 2] = uIm - vIm
                    val nextWr = wr * wLenCos - wi * wLenSin
                    wi = wr * wLenSin + wi * wLenCos
                    wr = nextWr
                }
                i += len
            }
            len = len shl 1
        }
    }
}