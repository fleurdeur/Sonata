package com.sonata.core.work.pipeline

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes PCM to a real audio file. AAC in MP4 is the normal output; if the
 * device encoder misbehaves the same samples are written as WAV, which
 * ExoPlayer plays just as well. The caller never gets a silent failure.
 */
object PcmFileWriter {

    const val BITRATE = 192_000

    fun write(pcm: ShortArray, sampleRate: Int, m4a: File): File {
        if (pcm.isEmpty()) return wav(pcm, sampleRate, File(m4a.parentFile, m4a.nameWithoutExtension + ".wav"))
        return runCatching { aac(pcm, sampleRate, m4a) }
            .getOrElse {
                android.util.Log.w("SonataRender", "AAC encode failed, writing WAV", it)
                m4a.delete()
                wav(pcm, sampleRate, File(m4a.parentFile, m4a.nameWithoutExtension + ".wav"))
            }
    }

    private fun aac(pcm: ShortArray, sampleRate: Int, outFile: File): File {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
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

            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val input = codec.getInputBuffer(inIndex)!!
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

    /** 16-bit mono PCM WAV, the always-available fallback. */
    fun wav(pcm: ShortArray, sampleRate: Int, outFile: File): File {
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
            val buffer = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            buffer.asShortBuffer().put(pcm)
            file.write(buffer.array())
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
