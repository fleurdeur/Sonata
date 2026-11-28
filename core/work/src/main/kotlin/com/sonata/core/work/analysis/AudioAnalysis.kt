package com.sonata.core.work.analysis

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * On-device audio analysis, no cloud: audio fingerprint for dedupe, BPM via
 * onset autocorrelation, musical key via chroma profile, integrated loudness,
 * waveform peaks and dead-air (silence) detection used by auto-trim.
 */
object AudioAnalysis {

    private val MAJOR_PROFILE = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val MINOR_PROFILE = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

    /** Average-hash style fingerprint over downsampled spectral chunks. */
    fun fingerprint(pcm: ShortArray, sampleRate: Int): String {
        val chunks = 64
        val chunkSize = (pcm.size / chunks).coerceAtLeast(1)
        var hash = 0L
        var prev = 0.0
        for (c in 0 until chunks) {
            var sum = 0.0
            val start = c * chunkSize
            val end = min(start + chunkSize, pcm.size)
            for (i in start until end) sum += abs(pcm[i].toDouble())
            val rms = sqrt(sum / (end - start).coerceAtLeast(1))
            if (c > 0) {
                val bit = rms > prev
                if (bit) hash = hash or (1L shl (c - 1))
            }
            prev = rms
        }
        return String.format("%016x", hash)
    }

    fun hammingDistance(a: String, b: String): Int {
        val x = runCatching { java.lang.Long.parseUnsignedLong(a, 16) }.getOrNull() ?: return 64
        val y = runCatching { java.lang.Long.parseUnsignedLong(b, 16) }.getOrNull() ?: return 64
        return java.lang.Long.bitCount(x xor y)
    }

    /** Duplicate when fingerprints differ by at most [tolerance] bits. */
    fun isDuplicate(a: String, b: String, tolerance: Int = 8): Boolean =
        hammingDistance(a, b) <= tolerance

    /** BPM: autocorrelation over an energy envelope, 70–190 BPM search. */
    fun detectBpm(pcm: ShortArray, sampleRate: Int): Int {
        if (pcm.size < sampleRate) return 120
        val hop = sampleRate / 100 // 10 ms hops
        val frames = pcm.size / hop
        val energy = DoubleArray(frames) { f ->
            var sum = 0.0
            for (i in 0 until hop) {
                val s = pcm[f * hop + i].toDouble()
                sum += s * s
            }
            sqrt(sum / hop)
        }
        // Onset strength = positive flux of the energy envelope.
        val flux = DoubleArray(frames) { f ->
            if (f == 0) 0.0 else maxOf(0.0, energy[f] - energy[f - 1])
        }
        val minLag = (60.0 / 190 * 100).roundToInt() // 190 BPM
        val maxLag = (60.0 / 70 * 100).roundToInt() // 70 BPM
        var bestLag = (60.0 / 120 * 100).roundToInt()
        var bestScore = -1.0
        for (lag in minLag..maxLag) {
            var score = 0.0
            for (f in 0 until frames - lag) score += flux[f] * flux[f + lag]
            // Normalise by the number of overlapping frames, otherwise the
            // shortest lag wins simply because it has more pairs to add.
            score /= (frames - lag).coerceAtLeast(1)
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        var bpm = 60.0 / (bestLag / 100.0)
        // Fold into the 80–180 range. In particular, a strong 70 BPM
        // autocorrelation peak is almost always the half-time pulse of a
        // 140 BPM record; keeping it at 70 made the library and Camelot mix
        // keys disagree by an octave.
        while (bpm < 80.0) bpm *= 2.0
        while (bpm > 180.0) bpm /= 2.0
        return bpm.roundToInt()
    }

    /** Key estimate with mode and a simple confidence margin. */
    data class KeyEstimate(
        val pitchClass: Int,
        val minor: Boolean,
        val confidence: Float,
    )

    /**
     * Musical key via FFT chroma against Krumhansl major/minor profiles.
     * Returns the tonic pitch class only (major interpretation) so older
     * pitch-shift callers keep their simple semitone arithmetic.
     */
    fun detectKey(pcm: ShortArray, sampleRate: Int): Int {
        val chroma = chromaProfile(pcm, sampleRate)
        if (chroma == null) return 0
        var best = 0
        var bestScore = -1.0
        for (root in 0 until 12) {
            val score = profileScore(chroma, root, MAJOR_PROFILE)
            if (score > bestScore) {
                bestScore = score
                best = root
            }
        }
        return best
    }

    /** Full key detection, including the relative minor mode. */
    fun detectKeyEstimate(pcm: ShortArray, sampleRate: Int): KeyEstimate {
        val chroma = chromaProfile(pcm, sampleRate) ?: return KeyEstimate(0, false, 0f)
        var best = 0
        var bestMinor = false
        var bestScore = -1.0
        var second = -1.0
        for (minor in booleanArrayOf(false, true)) {
            val profile = if (minor) MINOR_PROFILE else MAJOR_PROFILE
            for (root in 0 until 12) {
                val score = profileScore(chroma, root, profile)
                if (score > bestScore) {
                    second = bestScore
                    bestScore = score
                    best = root
                    bestMinor = minor
                } else if (score > second) {
                    second = score
                }
            }
        }
        val confidence = if (bestScore <= 0.0) 0f else {
            ((bestScore - second) / bestScore).toFloat().coerceIn(0f, 1f)
        }
        return KeyEstimate(best, bestMinor, confidence)
    }

    private fun chromaProfile(pcm: ShortArray, sampleRate: Int): DoubleArray? {
        if (pcm.size < 4096) return null
        val step = (sampleRate / 8000).coerceAtLeast(1)
        val effectiveRate = sampleRate / step
        val windowSize = 4096
        val windows = ((pcm.size / step - windowSize) / (windowSize / 2))
            .coerceAtLeast(1).coerceAtMost(16)
        val chroma = DoubleArray(12)
        val twoPi = 2.0 * Math.PI
        val re = DoubleArray(windowSize)
        val im = DoubleArray(windowSize)
        for (w in 0 until windows) {
            val base = w * windowSize / 2
            for (i in 0 until windowSize) {
                re[i] = (pcm[(base + i) * step] / 32768.0) *
                    (0.5 - 0.5 * cos(twoPi * i / windowSize))
                im[i] = 0.0
            }
            fft(re, im)
            for (bin in 1 until windowSize / 2) {
                val freq = bin.toDouble() * effectiveRate / windowSize
                if (freq < 65 || freq > 2100) continue
                val magnitude = sqrt(re[bin] * re[bin] + im[bin] * im[bin])
                if (magnitude <= 0.0) continue
                val midi = 69 + 12 * kotlin.math.ln(freq / 440.0) / Math.log(2.0)
                val pitchClass = ((midi.roundToInt() % 12) + 12) % 12
                // Squaring favours strong partials over broadband noise.
                chroma[pitchClass] += magnitude * magnitude
            }
        }
        val total = chroma.sum()
        if (total <= 0.0) return null
        for (i in 0 until 12) chroma[i] /= total
        return chroma
    }

    private fun profileScore(chroma: DoubleArray, root: Int, profile: DoubleArray): Double {
        var score = 0.0
        for (i in 0 until 12) score += chroma[(root + i) % 12] * profile[i]
        return score
    }

    /** In-place iterative radix-2 FFT; [re]/[im] must have a power-of-two size. */
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
        var length = 2
        while (length <= n) {
            val angle = -2.0 * Math.PI / length
            val wRe = cos(angle)
            val wIm = kotlin.math.sin(angle)
            var start = 0
            while (start < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until length / 2) {
                    val even = start + k
                    val odd = even + length / 2
                    val tRe = curRe * re[odd] - curIm * im[odd]
                    val tIm = curRe * im[odd] + curIm * re[odd]
                    re[odd] = re[even] - tRe
                    im[odd] = im[even] - tIm
                    re[even] += tRe
                    im[even] += tIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                start += length
            }
            length = length shl 1
        }
    }

    /** RMS-based integrated loudness, mapped into a LUFS-like scale. */
    fun loudnessLufs(pcm: ShortArray): Float {
        if (pcm.isEmpty()) return -70f
        var sum = 0.0
        for (s in pcm) {
            val v = s / 32768.0
            sum += v * v
        }
        val rms = sqrt(sum / pcm.size)
        if (rms <= 0) return -70f
        return (20 * kotlin.math.log10(rms) - 0.691).toFloat().coerceIn(-70f, 0f)
    }

    /** Normalized waveform peaks for thumbnails (default 64 buckets). */
    fun waveform(pcm: ShortArray, buckets: Int = 64): FloatArray {
        val out = FloatArray(buckets)
        if (pcm.isEmpty()) return out
        val bucketSize = (pcm.size / buckets).coerceAtLeast(1)
        for (b in 0 until buckets) {
            var peak = 0
            val start = b * bucketSize
            val end = min(start + bucketSize, pcm.size)
            for (i in start until end) {
                val v = abs(pcm[i].toInt())
                if (v > peak) peak = v
            }
            out[b] = peak / 32768f
        }
        return out
    }

    /**
     * Dead-air detection for auto-trim: returns (startMs, endMs) of trailing
     * + leading silence under [thresholdDbfs] with a small safety margin.
     */
    fun detectSilence(
        pcm: ShortArray,
        sampleRate: Int,
        thresholdDbfs: Float = -50f,
        minSilenceMs: Long = 200,
    ): Pair<Long, Long> {
        if (pcm.isEmpty()) return 0L to 0L
        val hop = sampleRate / 100
        val frames = pcm.size / hop
        val thresholdAmp = 32768.0 * 10f.pow(thresholdDbfs / 20)
        var firstLoud = 0
        var lastLoud = frames - 1
        for (f in 0 until frames) {
            if (framePeak(pcm, f, hop) > thresholdAmp) {
                firstLoud = f
                break
            }
        }
        for (f in frames - 1 downTo firstLoud) {
            if (framePeak(pcm, f, hop) > thresholdAmp) {
                lastLoud = f
                break
            }
        }
        val startMs = (firstLoud.toLong() * hop * 1000) / sampleRate
        val endMs = (lastLoud.toLong() * hop * 1000) / sampleRate
        return startMs to endMs
    }

    private fun framePeak(pcm: ShortArray, frame: Int, hop: Int): Double {
        var peak = 0
        for (i in 0 until hop) {
            val v = abs(pcm[frame * hop + i].toInt())
            if (v > peak) peak = v
        }
        return peak.toDouble()
    }

    /** Zero crossings, used for click-free loop and trim snapping. */
    fun nextZeroCrossing(pcm: ShortArray, from: Int, sampleRate: Int, searchMs: Long = 20): Int {
        val limit = min(pcm.size - 1, from + (sampleRate * searchMs / 1000).toInt())
        for (i in from..limit) {
            val a = pcm[i.coerceIn(0, pcm.size - 1)]
            val b = pcm[(i + 1).coerceIn(0, pcm.size - 1)]
            if ((a >= 0 && b < 0) || (a < 0 && b >= 0)) return i
        }
        return from
    }
}
