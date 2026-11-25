package com.sonata.core.work.pipeline

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * App-specific storage for imported audio plus the on-device PCM decoder used
 * by the analysis pipeline and the trim editor.
 */
@Singleton
class AudioStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val soundsDir: File get() = File(context.filesDir, "sounds").apply { mkdirs() }

    fun newSoundFile(extension: String = "m4a"): File =
        File(soundsDir, "sound_${System.currentTimeMillis()}.$extension")

    /** Downmixes + decodes any container to mono-agnostic 16-bit PCM. */
    suspend fun extractToPcm(uri: String): ShortArray = withContext(Dispatchers.IO) {
        val localFile: File
        if (uri.startsWith("http")) {
            localFile = File(context.cacheDir, "download_${System.currentTimeMillis()}")
            java.net.URL(uri).openStream().use { input ->
                localFile.outputStream().use { input.copyTo(it) }
            }
        } else {
            localFile = uriToFile(uri)
        }

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(localFile.absolutePath)
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
            format ?: throw IllegalArgumentException("No audio track found")
            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val pcm = ArrayList<Short>(1024 * 64)
            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false

            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outIndex >= 0) {
                    val out = codec.getOutputBuffer(outIndex)!!
                    out.position(bufferInfo.offset)
                    out.limit(bufferInfo.offset + bufferInfo.size)
                    while (out.remaining() >= 2) {
                        pcm += out.short
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawOutputEos = true
                    }
                }
            }
            codec.stop()
            codec.release()
            pcm.toShortArray()
        } finally {
            extractor.release()
            if (uri.startsWith("http")) localFile.delete()
        }
    }

    /** Copies shared/SAF content into app-specific storage (lossless copy). */
    fun store(uri: String): File {
        val outFile = newSoundFile()
        context.contentResolver.openInputStream(android.net.Uri.parse(uri))!!.use { input ->
            outFile.outputStream().use { input.copyTo(it) }
        }
        return outFile
    }

    private fun uriToFile(uri: String): File {
        if (uri.startsWith("file://")) return File(android.net.Uri.parse(uri).path!!)
        if (uri.startsWith("/")) return File(uri)
        val outFile = File(context.cacheDir, "decode_${System.currentTimeMillis()}")
        context.contentResolver.openInputStream(android.net.Uri.parse(uri))!!.use { input ->
            outFile.outputStream().use { input.copyTo(it) }
        }
        outFile.deleteOnExit()
        return outFile
    }
}
