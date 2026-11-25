package com.sonata.core.audio.render

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Offline PCM transforms used to turn a shared clip into a full-length track.
 *
 * Everything here is deterministic, allocation-light and testable on the JVM:
 * no Android APIs, no network, no time limits. Samples are signed 16-bit mono
 * at [sampleRate], the single format the whole analysis pipeline uses.
 */
object PcmTools {

    const val SAMPLE_RATE = 44_100

    // ---------------------------------------------------------------- utility

    fun peak(pcm: ShortArray): Int {
        var p = 0
        for (s in pcm) {
            val a = abs(s.toInt())
            if (a > p) p = a
        }
        return p
    }

    fun rms(pcm: ShortArray, from: Int = 0, to: Int = pcm.size): Double {
        if (to <= from) return 0.0
        var sum = 0.0
        for (i in from until to) {
            val v = pcm[i].toDouble() / 32768.0
            sum += v * v
        }
        return sqrt(sum / (to - from))
    }

    /** Scales the whole buffer so its peak sits at [targetDb] (default -1 dBFS). */
    fun normalize(pcm: ShortArray, targetDb: Double = -1.0): ShortArray {
        val p = peak(pcm)
        if (p == 0) return pcm
        val target = 32767.0 * 10.0.pow(targetDb / 20.0)
        val gain = target / p
        return ShortArray(pcm.size) { (pcm[it] * gain).roundToInt().coerceIn(-32768, 32767).toShort() }
    }

    /** Applies a linear gain in dB, clipping safely. */
    fun gain(pcm: ShortArray, db: Double): ShortArray {
        if (db == 0.0) return pcm
        val g = 10.0.pow(db / 20.0)
        return ShortArray(pcm.size) { (pcm[it] * g).roundToInt().coerceIn(-32768, 32767).toShort() }
    }

    fun fade(pcm: ShortArray, fadeInMs: Int, fadeOutMs: Int, sampleRate: Int = SAMPLE_RATE): ShortArray {
        if (pcm.isEmpty()) return pcm
        val out = pcm.copyOf()
        val inN = (sampleRate.toLong() * fadeInMs / 1000).toInt().coerceIn(0, out.size)
        val outN = (sampleRate.toLong() * fadeOutMs / 1000).toInt().coerceIn(0, out.size)
        for (i in 0 until inN) out[i] = (out[i] * (i.toDouble() / inN)).roundToInt().toShort()
        for (i in 0 until outN) {
            val idx = out.size - 1 - i
            out[idx] = (out[idx] * (i.toDouble() / outN)).roundToInt().toShort()
        }
        return out
    }

    // --------------------------------------------------------------- trimming

    private fun sampleAt(pcm: ShortArray, index: Int): Double {
        val i = index.coerceIn(0, pcm.size - 1)
        return pcm[i].toDouble()
    }

    /** Finds the first/last sample above [thresholdDb] (silence trim). */
    fun contentBounds(pcm: ShortArray, sampleRate: Int = SAMPLE_RATE, thresholdDb: Double = -45.0): IntRange {
        if (pcm.isEmpty()) return 0 until 0
        val hop = sampleRate / 100
        val threshold = 32768.0 * 10.0.pow(thresholdDb / 20.0)
        var first = 0
        var last = pcm.size - 1
        var f = 0
        while (f + hop <= pcm.size) {
            var peak = 0
            for (i in f until f + hop) if (abs(pcm[i].toInt()) > peak) peak = abs(pcm[i].toInt())
            if (peak > threshold) { first = f; break }
            f += hop
        }
        var b = pcm.size - hop
        while (b >= first) {
            var peak = 0
            for (i in b until b + hop) if (abs(pcm[i].toInt()) > peak) peak = abs(pcm[i].toInt())
            if (peak > threshold) { last = (b + hop - 1).coerceAtMost(pcm.size - 1); break }
            b -= hop
        }
        return first.coerceAtLeast(0)..last.coerceAtLeast(first)
    }

    // ------------------------------------------------------------- loop build

    /**
     * Repeats [pcm] until it reaches [targetMs], crossfading every join with an
     * equal-power curve so infinite loops stay click-free.
     */
    fun loopToLength(
        pcm: ShortArray,
        targetMs: Long,
        crossfadeMs: Int = 40,
        sampleRate: Int = SAMPLE_RATE,
    ): ShortArray {
        if (pcm.isEmpty()) return pcm
        val target = (targetMs * sampleRate / 1000).toInt().coerceAtLeast(pcm.size)
        val xf = (sampleRate.toLong() * crossfadeMs / 1000).toInt().coerceIn(0, pcm.size / 4)
        if (xf == 0) {
            val out = ShortArray(target)
            var pos = 0
            while (pos < target) {
                val n = min(pcm.size, target - pos)
                System.arraycopy(pcm, 0, out, pos, n)
                pos += n
            }
            return out
        }
        val acc = DoubleArray(target + xf + pcm.size)
        for (i in pcm.indices) acc[i] = pcm[i].toDouble()
        var length = pcm.size
        while (length < target) {
            // Equal-power crossfade between the tail already written and the
            // head of the loop, so the seam never clicks.
            for (i in 0 until xf) {
                val t = i.toDouble() / xf
                val a = acc[length - xf + i]
                val b = pcm[i].toDouble()
                acc[length - xf + i] = a * cos(t * PI / 2) + b * sin(t * PI / 2)
            }
            for (i in xf until pcm.size) acc[length + i - xf] = pcm[i].toDouble()
            length += pcm.size - xf
        }
        return ShortArray(target) { acc[it].roundToInt().coerceIn(-32768, 32767).toShort() }
    }

    // ---------------------------------------------------------- time stretch

    /**
     * WSOLA time stretch: changes tempo while keeping pitch. [speed] > 1 is
     * faster and shorter, 0.8 is a slow-down.
     */
    fun timeStretch(pcm: ShortArray, speed: Float, sampleRate: Int = SAMPLE_RATE): ShortArray {
        require(speed > 0f) { "speed must be positive" }
        if (abs(speed - 1f) < 0.001f || pcm.size < 4096) return pcm
        val frame = 2048
        val hopOut = frame / 2
        val outLen = (pcm.size / speed).roundToInt().coerceAtLeast(1)
        val window = hann(frame)
        val acc = DoubleArray(outLen + frame)
        var previousIn = 0
        var outPos = 0
        // Keep the analysis position tied to the requested tempo. Searching a
        // full hop around the previous frame lets the matcher cancel the speed
        // change entirely; a small phase window keeps the tempo exact.
        val tolerance = (sampleRate * 0.001).toInt().coerceIn(16, 48)
        val stride = 4
        while (outPos < outLen) {
            val target = (outPos * speed).roundToInt()
            if (target + frame > pcm.size) break
            var bestSrc = target
            if (outPos > 0) {
                // The new frame's first half overlaps the previous output tail.
                // Pick the nearby input offset that continues that waveform.
                val natural = previousIn + hopOut
                var best = Double.MAX_VALUE
                var shift = -tolerance
                while (shift <= tolerance) {
                    val candidate = target + shift
                    if (candidate >= 0 && candidate + frame <= pcm.size &&
                        natural + hopOut <= pcm.size
                    ) {
                        var cost = 0.0
                        var i = 0
                        while (i < hopOut) {
                            val d = pcm[candidate + i].toDouble() - pcm[natural + i].toDouble()
                            cost += d * d
                            i += stride
                        }
                        if (cost < best) {
                            best = cost
                            bestSrc = candidate
                        }
                    }
                    shift += stride
                }
            }
            val count = min(frame, outLen + frame - outPos)
            for (i in 0 until count) {
                acc[outPos + i] += sampleAt(pcm, bestSrc + i) * window[i]
            }
            previousIn = bestSrc
            outPos += hopOut
        }

        // The last few milliseconds can be shorter than one output hop; copy the
        // matching source tail instead of leaving a silent hole.
        if (outPos < outLen) {
            val srcStart = (outPos * speed).toInt().coerceIn(0, pcm.size - 1)
            for (i in outPos until outLen) {
                acc[i] = pcm[(srcStart + i - outPos).coerceAtMost(pcm.size - 1)].toDouble()
            }
        }
        val out = ShortArray(outLen) {
            acc[it].roundToInt().coerceIn(-32768, 32767).toShort()
        }
        return fade(out, 3, 3, sampleRate)
    }

    /** Resampling pitch+tempo shift, the "nightcore / vinyl" character. */
    fun resample(pcm: ShortArray, speed: Float): ShortArray {
        if (abs(speed - 1f) < 0.001f || pcm.isEmpty()) return pcm
        val outLen = (pcm.size / speed).toInt().coerceAtLeast(1)
        val out = ShortArray(outLen)
        for (i in 0 until outLen) {
            val src = i * speed
            val i0 = src.toInt()
            val frac = src - i0
            val a = sampleAt(pcm, i0)
            val b = sampleAt(pcm, i0 + 1)
            out[i] = (a + (b - a) * frac).roundToInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    // ------------------------------------------------------------------ gain

    /** Soft low-shelf lift used for the "bass boosted" TikTok sound. */
    fun bassBoost(pcm: ShortArray, db: Double, cutoffHz: Double = 110.0, sampleRate: Int = SAMPLE_RATE): ShortArray {
        if (db == 0.0) return pcm
        val g = 10.0.pow(db / 20.0) - 1.0
        val alpha = 1.0 - kotlin.math.exp(-2.0 * PI * cutoffHz / sampleRate)
        var low = 0.0
        return ShortArray(pcm.size) { i ->
            val x = pcm[i].toDouble()
            low += alpha * (x - low)
            (x + low * g).roundToInt().coerceIn(-32768, 32767).toShort()
        }
    }

    // --------------------------------------------------------------- reverb

    /**
     * Schroeder reverb: four damped parallel comb filters into two allpass
     * stages, mixed against the dry signal. [mix] 0..1.
     */
    fun reverb(
        pcm: ShortArray,
        mix: Float,
        sampleRate: Int = SAMPLE_RATE,
        decay: Float = 0.78f,
        damping: Float = 0.25f,
    ): ShortArray {
        if (mix <= 0f || pcm.isEmpty()) return pcm
        val scale = sampleRate / 44_100.0
        val combs = intArrayOf(1557, 1617, 1491, 1422).map { (it * scale).toInt().coerceAtLeast(1) }
        val allpasses = intArrayOf(225, 556).map { (it * scale).toInt().coerceAtLeast(1) }
        val wet = DoubleArray(pcm.size)
        for (delay in combs) {
            val buf = DoubleArray(delay)
            var idx = 0
            var filter = 0.0
            for (i in pcm.indices) {
                val y = buf[idx]
                filter = y * (1.0 - damping) + filter * damping
                buf[idx] = pcm[i] / 32768.0 + filter * decay
                idx = (idx + 1) % delay
                wet[i] += y * 0.25
            }
        }
        for (delay in allpasses) {
            val buf = DoubleArray(delay)
            var idx = 0
            for (i in wet.indices) {
                val buffered = buf[idx]
                val y = -wet[i] + buffered
                buf[idx] = wet[i] + buffered * 0.5
                idx = (idx + 1) % delay
                wet[i] = y
            }
        }
        val wetLevel = mix.coerceIn(0f, 1f) * 1.6
        val dryLevel = 1.0 - mix.coerceIn(0f, 1f) * 0.55
        return ShortArray(pcm.size) { i ->
            ((pcm[i] / 32768.0) * dryLevel + wet[i] * wetLevel)
                .times(32767.0).roundToInt().coerceIn(-32768, 32767).toShort()
        }
    }

    // -------------------------------------------------------- effect estimate

    /**
     * Estimates how wet a clip is: the ratio of energy that continues after
     * note offsets (reverb tail) to the energy at onsets. 0.1 is dry, >0.5 is a
     * clearly washed-out clip.
     */
    fun reverbTailRatio(pcm: ShortArray, sampleRate: Int = SAMPLE_RATE): Double {
        if (pcm.size < sampleRate / 2) return 0.0
        val hop = sampleRate / 100
        val frames = pcm.size / hop
        if (frames < 20) return 0.0
        val energy = DoubleArray(frames) { f ->
            var sum = 0.0
            for (i in 0 until hop) { val v = pcm[f * hop + i] / 32768.0; sum += v * v }
            sqrt(sum / hop)
        }
        var loud = 0.0
        var tail = 0.0
        var loudFrames = 0
        var tailFrames = 0
        for (f in 1 until frames) {
            if (energy[f] > energy[f - 1] * 1.35) {
                loud += energy[f]; loudFrames++
            } else if (energy[f] > energy[f - 1]) {
                tail += energy[f]; tailFrames++
            }
        }
        if (loudFrames == 0 || tailFrames == 0 || loud == 0.0) return 0.0
        return (tail / tailFrames) / (loud / loudFrames)
    }

    /** Semitone difference between two pitch-class indices, folded to +/-6. */
    fun semitoneDelta(fromKey: Int, toKey: Int): Int {
        var d = (toKey - fromKey) % 12
        if (d > 6) d -= 12
        if (d < -6) d += 12
        return d
    }

    private fun hann(size: Int): DoubleArray = DoubleArray(size) { i ->
        0.5 - 0.5 * cos(2.0 * PI * i / (size - 1))
    }
}