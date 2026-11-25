package com.sonata.core.work.pipeline

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.annotation.WorkerThread
import com.sonata.core.audio.render.PcmTools
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Basic facts about an imported file, read without decoding it. */
data class AudioFileInfo(
    val hasAudio: Boolean,
    val hasVideo: Boolean,
    val durationMs: Long,
    val sampleRate: Int,
    val channels: Int,
)

/**
 * App-specific storage for imported audio plus the on-device PCM decoder used
 * by the analysis pipeline, the renderer and the trim editor.
 *
 * Every decoder path returns **mono 44.1 kHz 16-bit PCM**, the single format
 * the analysis and DSP stages understand. Downmixing and resampling here means
 * durations, BPM and filters are identical for a mono voice memo, a stereo
 * TikTok video and a 48 kHz stream.
 */
@Singleton
class AudioStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val soundsDir: File get() = File(context.filesDir, "sounds").apply { mkdirs() }

    fun newSoundFile(extension: String = "m4a"): File =
        File(soundsDir, "sound_${System.currentTimeMillis()}.$extension")

    fun cacheFile(name: String = "download_${System.currentTimeMillis()}"): File =
        File(context.cacheDir, name)

    // ------------------------------------------------------------------ probe

    suspend fun info(uri: String): AudioFileInfo = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        try {
            val pfd = openDescriptor(uri) ?: return@withContext AudioFileInfo(false, false, 0, 44_100, 1)
            pfd.use { descriptor ->
                extractor.setDataSource(descriptor.fileDescriptor)
                var audio = false
                var video = false
                var rate = 44_100
                var channels = 1
                var duration = 0L
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                    if (mime.startsWith("audio/")) {
                        audio = true
                        rate = format.intOr(MediaFormat.KEY_SAMPLE_RATE, 44_100)
                        channels = format.intOr(MediaFormat.KEY_CHANNEL_COUNT, 1)
                        duration = format.longOr(MediaFormat.KEY_DURATION, 0L) / 1000
                    }
                    if (mime.startsWith("video/")) video = true
                }
                AudioFileInfo(audio, video, duration, rate, channels)
            }
        } finally {
            extractor.release()
        }
    }

    // ----------------------------------------------------------------- decode

    /**
     * Decodes any container to mono 44.1 kHz PCM. Feed it a local file path, a
     * content:// URI or an http(s) URL. Decoding stops at [maxDurationMs] so a
     * corrupted or endless stream cannot exhaust memory.
     */
    suspend fun extractToPcm(uri: String, maxDurationMs: Long = 15 * 60 * 1000): ShortArray =
        withContext(Dispatchers.IO) {
            var temp: File? = null
            try {
                val source = when {
                    uri.startsWith("http") -> {
                        temp = cacheFile()
                        java.net.URL(uri).openStream().use { input ->
                            temp.outputStream().use { input.copyTo(it) }
                        }
                        temp.absolutePath
                    }
                    else -> uri
                }
                decodeMono(source, maxDurationMs)
            } finally {
                temp?.delete()
            }
        }

    private fun decodeMono(uri: String, maxDurationMs: Long): ShortArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            val pfd = openDescriptor(uri) ?: throw IllegalArgumentException("Cannot open audio source")
            pfd.use { descriptor ->
                extractor.setDataSource(descriptor.fileDescriptor)
                var trackIndex = -1
                var format: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val f = extractor.getTrackFormat(i)
                    if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                        trackIndex = i
                        format = f
                        break
                    }
                }
                val audioFormat = format ?: throw IllegalArgumentException("No audio track in this file")
                extractor.selectTrack(trackIndex)

                val mime = audioFormat.getString(MediaFormat.KEY_MIME)!!
                val sourceRate = audioFormat.intOr(MediaFormat.KEY_SAMPLE_RATE, 44_100)
                val channels = audioFormat.intOr(MediaFormat.KEY_CHANNEL_COUNT, 1).coerceAtLeast(1)
                val durationUs = audioFormat.longOr(MediaFormat.KEY_DURATION, 0L)
                val limitSamples = (sourceRate.toLong() * maxDurationMs / 1000).toInt()

                var decoder = MediaCodec.createDecoderByType(mime)
                decoder.configure(audioFormat, null, null, 0)
                decoder.start()
                codec = decoder

                val chunks = ArrayList<ShortArray>(64)
                var collected = 0
                val bufferInfo = MediaCodec.BufferInfo()
                var sawInputEos = false
                var sawOutputEos = false
                var outputChannels = channels

                while (!sawOutputEos) {
                    if (!sawInputEos) {
                        val inIndex = decoder.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            val inputBuffer = decoder.getInputBuffer(inIndex)!!
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(
                                    inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                sawInputEos = true
                            } else {
                                decoder.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIndex = decoder.dequeueOutputBuffer(bufferInfo, 10_000)
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        outputChannels = decoder.outputFormat.intOr(MediaFormat.KEY_CHANNEL_COUNT, channels)
                    } else if (outIndex >= 0) {
                        if (bufferInfo.size > 0) {
                            val buffer = decoder.getOutputBuffer(outIndex)!!
                            buffer.position(bufferInfo.offset)
                            buffer.limit(bufferInfo.offset + bufferInfo.size)
                            val shorts = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val chunk = ShortArray(shorts.remaining())
                            shorts.get(chunk)
                            chunks += chunk
                            collected += chunk.size
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            sawOutputEos = true
                        }
                    }
                    if (collected > limitSamples * outputChannels) {
                        sawOutputEos = true
                        sawInputEos = true
                    }
                }
                decoder.stop()
                decoder.release()
                codec = null

                val interleaved = ShortArray(collected)
                var offset = 0
                for (chunk in chunks) {
                    System.arraycopy(chunk, 0, interleaved, offset, chunk.size)
                    offset += chunk.size
                }
                val mono = downmix(interleaved, outputChannels)
                return resample(mono, sourceRate, PcmTools.SAMPLE_RATE)
            }
        } finally {
            codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
            extractor.release()
        }
    }

    private fun downmix(interleaved: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return interleaved
        val frames = interleaved.size / channels
        val mono = ShortArray(frames)
        for (f in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) sum += interleaved[f * channels + c]
            mono[f] = (sum / channels).toShort()
        }
        return mono
    }

    private fun resample(pcm: ShortArray, from: Int, to: Int): ShortArray {
        if (from == to || pcm.isEmpty()) return pcm
        val ratio = to.toDouble() / from
        val outLen = (pcm.size * ratio).toInt().coerceAtLeast(1)
        val out = ShortArray(outLen)
        for (i in 0 until outLen) {
            val src = i / ratio
            val i0 = src.toInt()
            val frac = src - i0
            val a = pcm[i0.coerceIn(0, pcm.size - 1)].toDouble()
            val b = pcm[(i0 + 1).coerceIn(0, pcm.size - 1)].toDouble()
            out[i] = (a + (b - a) * frac).toInt().toShort()
        }
        return out
    }

    // ----------------------------------------------------------------- store

    /** Copies shared/SAF content into app-specific storage (lossless copy). */
    @WorkerThread
    fun store(uri: String): File {
        val outFile = newSoundFile()
        context.contentResolver.openInputStream(android.net.Uri.parse(uri))!!.use { input ->
            outFile.outputStream().use { input.copyTo(it) }
        }
        return outFile
    }

    val soundDirectory: File get() = soundsDir

    private fun openDescriptor(uri: String): android.os.ParcelFileDescriptor? {
        if (uri.startsWith("file://")) {
            return android.os.ParcelFileDescriptor.open(
                File(android.net.Uri.parse(uri).path!!),
                android.os.ParcelFileDescriptor.MODE_READ_ONLY,
            )
        }
        if (uri.startsWith("/")) {
            return android.os.ParcelFileDescriptor.open(
                File(uri),
                android.os.ParcelFileDescriptor.MODE_READ_ONLY,
            )
        }
        return runCatching {
            context.contentResolver.openFileDescriptor(android.net.Uri.parse(uri), "r")
        }.getOrNull()
    }

    private fun MediaFormat.intOr(key: String, fallback: Int): Int =
        runCatching { getInteger(key) }.getOrDefault(fallback)

    private fun MediaFormat.longOr(key: String, fallback: Long): Long =
        runCatching { getLong(key) }.getOrDefault(fallback)
}
