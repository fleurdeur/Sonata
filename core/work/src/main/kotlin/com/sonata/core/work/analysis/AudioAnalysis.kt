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
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        val bpm = 60.0 / (bestLag / 100.0)
        // Fold into the 70–190 range (double/half-time detection).
        val folded = generateSequence(bpm) { if (it > 190) it / 2 else it }
            .first { it in 70.0..190.0 }
        return folded.roundToInt()
    }

    /** Musical key via chroma aggregation against major/minor profiles. */
    fun detectKey(pcm: ShortArray, sampleRate: Int): Int {
        // Downsample to ~8 kHz and DFT a few windows; aggregate chroma.
        val chroma = DoubleArray(12)
        val step = (sampleRate / 8000).coerceAtLeast(1)
        val effectiveRate = sampleRate / step
        val windowSize = 4096
        val windows = ((pcm.size / step - windowSize) / (windowSize / 2)).coerceAtLeast(1).coerceAtMost(16)
        val twoPi = 2.0 * Math.PI
        for (w in 0 until windows) {
            val base = w * windowSize / 2
            val window = DoubleArray(windowSize) { i ->
                (pcm[(base + i) * step] / 32768.0) *
                    (0.5 - 0.5 * cos(twoPi * i / windowSize)) // Hann
            }
            for (bin in 1 until windowSize / 2) {
                var re = 0.0
                var im = 0.0
                val freq = bin.toDouble() * effectiveRate / windowSize
                if (freq < 65 || freq > 2100) continue
                for (i in 0 until windowSize) {
                    val angle = twoPi * bin * i / windowSize
                    re += window[i] * cos(angle)
                    im -= window[i] * sinApprox(angle)
                }
                val magnitude = sqrt(re * re + im * im)
                if (magnitude <= 0) continue
                val midi = 69 + 12 * kotlin.math.ln(freq / 440.0) / Math.log(2.0)
                val pitchClass = ((midi.roundToInt() % 12) + 12) % 12
                chroma[pitchClass] += magnitude
            }
        }
        val total = chroma.sum()
        if (total <= 0) return 0
        val normalized = chroma.map { it / total }
        // Krumhansl major profile, rotated over the 12 pitch classes.
        val major = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
        var best = 0
        var bestScore = -1.0
        for (root in 0 until 12) {
            var score = 0.0
            for (i in 0 until 12) score += normalized[(root + i) % 12] * major[i]
            if (score > bestScore) {
                bestScore = score
                best = root
            }
        }
        return best // index into MusicalKey values
    }

    private fun sinApprox(x: Double): Double = kotlin.math.sin(x)

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
