package com.sonata.core.work.pipeline

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes PCM to a real audio file. AAC in MP4 is the normal output; if the
 * device encoder misbehaves, produced a short file or reports a wrong
 * duration, the same samples are written as WAV. The caller never gets a
 * silently truncated track or a .m4a file that is really WAV bytes.
 */
object PcmFileWriter {

    const val BITRATE = 192_000
    private const val FALLBACK_BITRATE = 128_000

    fun write(
        pcm: ShortArray,
        sampleRate: Int,
        m4a: File,
        onProgress: ((Float) -> Unit)? = null,
    ): File {
        val wavFile = File(m4a.parentFile, m4a.nameWithoutExtension + ".wav")
        if (pcm.isEmpty()) return wav(pcm, sampleRate, wavFile, onProgress)
        var encoded = runCatching { aac(pcm, sampleRate, m4a, BITRATE, onProgress) }
            .onFailure { android.util.Log.w("SonataRender", "AAC encoder failed; retrying safer bitrate", it) }
            .getOrNull()
        if (encoded != null && isComplete(encoded, pcm.size, sampleRate)) {
            onProgress?.invoke(1f)
            return encoded
        }
        // Some vendor encoders reject or truncate high mono bitrates. One
        // cheaper retry is still far better than dropping the import.
        runCatching { encoded?.delete() }
        m4a.delete()
        encoded = runCatching { aac(pcm, sampleRate, m4a, FALLBACK_BITRATE, onProgress) }
            .onFailure { android.util.Log.w("SonataRender", "AAC retry failed; using WAV", it) }
            .getOrNull()
        if (encoded != null && isComplete(encoded, pcm.size, sampleRate)) {
            onProgress?.invoke(1f)
            return encoded
        }
        runCatching { encoded?.delete() }
        m4a.delete()
        android.util.Log.w("SonataRender", "AAC output failed duration verification; writing exact WAV")
        onProgress?.invoke(0.96f)
        return wav(pcm, sampleRate, wavFile, onProgress)
    }

    /** Container duration check: reject a file that lost more than 250 ms. */
    private fun isComplete(file: File, samples: Int, sampleRate: Int): Boolean {
        if (!file.isFile || file.length() < 1_024L) return false
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            var durationUs = 0L
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    durationUs = runCatching { format.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L)
                    break
                }
            }
            if (durationUs <= 0L) return true // no reliable container duration
            val expectedMs = samples.toLong() * 1_000L / sampleRate
            val actualMs = durationUs / 1_000L
            actualMs >= expectedMs - 250L && actualMs <= expectedMs + 3_000L
        } catch (t: Throwable) {
            false
        } finally {
            extractor.release()
        }
    }

    private fun aac(
        pcm: ShortArray,
        sampleRate: Int,
        outFile: File,
        bitrate: Int,
        onProgress: ((Float) -> Unit)?,
    ): File {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32 * 1024)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxerStarted = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val packet = ByteBuffer.allocateDirect(32 * 1024)
            val info = MediaCodec.BufferInfo()
            var samplePosition = 0
            var inputDone = false
            var outputDone = false
            var lastProgress = -1f
            val startedAt = SystemClock.elapsedRealtime()
            // A hardware encoder normally finishes far faster than real time.
            // If a vendor codec wedges, fail over to WAV instead of spinning
            // forever while the user sees "Encoding".
            val timeoutMs = minOf(
                90_000L,
                maxOf(45_000L, pcm.size.toLong() * 1_000L / sampleRate * 2L),
            )

            while (!outputDone) {
                if (SystemClock.elapsedRealtime() - startedAt > timeoutMs) {
                    throw IllegalStateException("The AAC encoder stopped responding")
                }
                if (!inputDone) {
                    // Do not wait on input. Waiting here and then waiting for
                    // output made a long track crawl at ~10 ms per buffer.
                    val inIndex = codec.dequeueInputBuffer(0)
                    if (inIndex >= 0) {
                        val input = codec.getInputBuffer(inIndex)
                            ?: throw IllegalStateException("The AAC encoder returned no input buffer")
                        input.clear()
                        val remaining = pcm.size - samplePosition
                        if (remaining <= 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0,
                                presentationUs(samplePosition, sampleRate),
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            val count = minOf(remaining, input.capacity() / 2)
                            val shorts = input.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            shorts.put(pcm, samplePosition, count)
                            codec.queueInputBuffer(
                                inIndex, 0, count * 2,
                                presentationUs(samplePosition, sampleRate), 0,
                            )
                            samplePosition += count
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                } else if (outIndex >= 0) {
                    if (info.size > 0 && muxerStarted) {
                        packet.clear()
                        val output = codec.getOutputBuffer(outIndex)!!
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        packet.put(output)
                        packet.flip()
                        muxer.writeSampleData(trackIndex, packet, info)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
                val fraction = if (pcm.isEmpty()) 1f else samplePosition.toFloat() / pcm.size
                if (onProgress != null && fraction - lastProgress >= 0.01f) {
                    lastProgress = fraction
                    onProgress(fraction.coerceIn(0f, 1f))
                }
            }
            return outFile
        } finally {
            runCatching { codec.stop() }
            codec.release()
            if (muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    private fun presentationUs(samplePosition: Int, sampleRate: Int): Long =
        samplePosition.toLong() * 1_000_000L / sampleRate

    /** 16-bit mono PCM WAV, the exact always-available fallback. */
    fun wav(
        pcm: ShortArray,
        sampleRate: Int,
        outFile: File,
        onProgress: ((Float) -> Unit)? = null,
    ): File {
        outFile.parentFile?.mkdirs()
        val dataSize = pcm.size * 2
        RandomAccessFile(outFile, "rw").use { file ->
            file.setLength(0)
            file.write("RIFF".toByteArray())
            file.writeIntLe(36 + dataSize)
            file.write("WAVE".toByteArray())
            file.write("fmt ".toByteArray())
            file.writeIntLe(16)
            file.writeShortLe(1) // PCM
            file.writeShortLe(1) // mono
            file.writeIntLe(sampleRate)
            file.writeIntLe(sampleRate * 2)
            file.writeShortLe(2)
            file.writeShortLe(16)
            file.write("data".toByteArray())
            file.writeIntLe(dataSize)
            // Stream the samples instead of materializing a second copy of the
            // whole song as a byte array. A five-minute track is ~26 MB of PCM.
            val buffer = ByteArray(16 * 1024)
            var index = 0
            while (index < pcm.size) {
                val count = minOf((buffer.size / 2), pcm.size - index)
                for (j in 0 until count) {
                    val value = pcm[index + j].toInt()
                    buffer[j * 2] = (value and 0xFF).toByte()
                    buffer[j * 2 + 1] = ((value shr 8) and 0xFF).toByte()
                }
                file.write(buffer, 0, count * 2)
                index += count
                if (onProgress != null && index % (64 * 1024) < count) {
                    onProgress(index.toFloat() / pcm.size)
                }
            }
            onProgress?.invoke(1f)
        }
        return outFile
    }

    private fun RandomAccessFile.writeIntLe(value: Int) {
        write(byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte(),
        ))
    }

    private fun RandomAccessFile.writeShortLe(value: Int) {
        write(byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte()))
    }
}
