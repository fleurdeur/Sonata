package com.sonata.core.work.pipeline

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.sonata.core.audio.render.PcmTools
import com.sonata.core.common.di.IoDispatcher
import com.sonata.core.database.dao.SoundDao
import com.sonata.core.database.entity.SoundEntity
import com.sonata.core.database.entity.encodeWaveform
import com.sonata.core.domain.model.ImportJob
import com.sonata.core.domain.model.ImportStage
import com.sonata.core.domain.model.MusicalKey
import com.sonata.core.domain.repository.ImportRepository
import com.sonata.core.extractor.provider.LinkResolverRegistry
import com.sonata.core.extractor.tiktok.MediaDownloader
import com.sonata.core.extractor.tiktok.TikTokUrls
import com.sonata.core.work.analysis.AudioAnalysis
import com.sonata.core.work.tiktok.TikTokPipeline
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * The measured import pipeline, run as a foreground WorkManager task with
 * exponential backoff. One path for every entry point:
 *
 * RESOLVE -> DOWNLOAD -> EXTRACT (mono 44.1 kHz PCM) -> DEDUPE -> ANALYZE ->
 * RENDER -> STORE.
 *
 * A shared TikTok link gets its own pipeline: the video is saved, its audio is
 * separated, the full song is fetched when available, the edit's effects are
 * measured and the finished track is rendered into app storage.
 */
@HiltWorker
class ImportWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val resolvers: LinkResolverRegistry,
    private val importRepository: ImportRepository,
    private val soundDao: SoundDao,
    private val audioStore: AudioStore,
    private val downloader: MediaDownloader,
    private val tiktokPipeline: TikTokPipeline,
    @IoDispatcher private val io: CoroutineDispatcher,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(io) {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return@withContext Result.failure()
        val sourceUri = inputData.getString(KEY_SOURCE_URI) ?: return@withContext Result.failure()
        val isLink = inputData.getBoolean(KEY_IS_LINK, false)

        suspend fun progress(stage: ImportStage, fraction: Float, title: String = "", error: String? = null) {
            runCatching {
                importRepository.update(ImportJob(jobId, stage, fraction, title, sourceUri, error))
            }
        }

        try {
            val result: Stored = when {
                isLink && TikTokUrls.isTikTok(sourceUri) -> importTikTok(sourceUri, ::progress)
                isLink -> importLink(sourceUri, ::progress)
                else -> importLocal(sourceUri, ::progress)
            }

            progress(ImportStage.DONE, 1f, result.title)
            Result.success(
                workDataOf(
                    KEY_SOUND_ID to result.soundId,
                    KEY_DUPLICATE to result.duplicate,
                    KEY_TRIM_START to result.trimStart,
                    KEY_TRIM_END to result.trimEnd,
                ),
            )
        } catch (t: Throwable) {
            android.util.Log.e("SonataImport", "import failed", t)
            if (runAttemptCount < 2) {
                // Keep the job visible as queued: WorkManager will retry with
                // exponential backoff and the sheet shows the last error.
                progress(ImportStage.QUEUED, 0f, error = friendlyError(t))
                Result.retry()
            } else {
                progress(ImportStage.FAILED, 1f, error = friendlyError(t))
                Result.failure()
            }
        }
    }

    // ------------------------------------------------------------- TikTok

    private suspend fun importTikTok(
        url: String,
        progress: suspend (ImportStage, Float, String, String?) -> Unit,
    ): Stored {
        var lastTitle = ""
        val track = tiktokPipeline.process(url) { stage, fraction, title ->
            if (title.isNotBlank()) lastTitle = title
            progress(stage, fraction, title, null)
        }
        progress(ImportStage.ANALYZING, 0.95f, track.title, null)
        val file = audioStore.newSoundFile("m4a")
        val written = PcmFileWriter.write(track.pcm, PcmTools.SAMPLE_RATE, file)
        return persist(
            pcm = track.pcm,
            file = written,
            title = track.title.ifBlank { lastTitle },
            artist = track.artist,
            source = "TIKTOK",
            coverPath = track.coverPath,
            tags = track.tags,
            progress = progress,
        )
    }

    // -------------------------------------------------------------- links

    private suspend fun importLink(
        url: String,
        progress: suspend (ImportStage, Float, String, String?) -> Unit,
    ): Stored {
        progress(ImportStage.RESOLVING, 0.04f, "Resolving link", null)
        val resolver = resolvers.forUrl(url)
            ?: throw IllegalArgumentException("No importer handles this link")
        val resolved = resolver.resolve(url) { fraction, label ->
            kotlinx.coroutines.runBlocking { progress(ImportStage.DOWNLOADING, 0.08f + fraction * 0.42f, label, null) }
        }
        val cache = audioStore.cacheFile("link_${System.currentTimeMillis()}")
        progress(ImportStage.DOWNLOADING, 0.5f, "Saving audio", null)
        downloader.download(resolved.audioUrl, cache) { fraction ->
            kotlinx.coroutines.runBlocking { progress(ImportStage.DOWNLOADING, 0.5f + fraction * 0.15f, "Saving audio", null) }
        }
        val pcm = decode(cache.absolutePath, progress, 0.7f)
        val info = audioStore.info(cache.absolutePath)
        val title = resolved.title.ifBlank { "Imported sound" }
        cache.delete()
        return finishPcm(
            pcm = pcm,
            encode = true,
            title = title,
            artist = resolved.author,
            source = "LINK",
            coverPath = null,
            tags = listOf("link"),
            progress = progress,
        )
    }

    // -------------------------------------------------------------- local

    private suspend fun importLocal(
        uri: String,
        progress: suspend (ImportStage, Float, String, String?) -> Unit,
    ): Stored {
        progress(ImportStage.EXTRACTING, 0.15f, "Reading file", null)
        val info = audioStore.info(uri)
        val pcm = decode(uri, progress, 0.35f)
        val title = displayName(uri)
        // Video shares (and anything with a picture) must become audio files.
        return if (info.hasVideo) {
            finishPcm(
                pcm = pcm, encode = true, title = title, artist = null,
                source = "SHARE", coverPath = null, tags = listOf("shared"), progress = progress,
            )
        } else {
            val stored = audioStore.store(uri)
            persist(
                pcm = pcm, file = stored, title = title, artist = null,
                source = "SHARE", coverPath = null, tags = listOf("shared"), progress = progress,
            )
        }
    }

    // ------------------------------------------------------------- shared

    private suspend fun finishPcm(
        pcm: ShortArray,
        encode: Boolean,
        title: String,
        artist: String?,
        source: String,
        coverPath: String?,
        tags: List<String>,
        progress: suspend (ImportStage, Float, String, String?) -> Unit,
    ): Stored {
        progress(ImportStage.RENDERING, 0.85f, "Writing track", null)
        val file = audioStore.newSoundFile("m4a")
        val written = PcmFileWriter.write(pcm, PcmTools.SAMPLE_RATE, file)
        return persist(pcm, written, title, artist, source, coverPath, tags, progress)
    }

    private suspend fun decode(
        path: String,
        progress: suspend (ImportStage, Float, String, String?) -> Unit,
        fraction: Float,
    ): ShortArray {
        progress(ImportStage.EXTRACTING, fraction, "Extracting audio", null)
        return audioStore.extractToPcm(path)
    }

    private suspend fun persist(
        pcm: ShortArray,
        file: File,
        title: String,
        artist: String?,
        source: String,
        coverPath: String?,
        tags: List<String>,
        progress: suspend (ImportStage, Float, String, String?) -> Unit,
    ): Stored {
        progress(ImportStage.ANALYZING, 0.9f, title, null)
        val fingerprint = AudioAnalysis.fingerprint(pcm, PcmTools.SAMPLE_RATE)
        val existing = soundDao.findByFingerprint(fingerprint)
        if (existing != null) {
            file.delete()
            return Stored(existing.id, existing.title, duplicate = true)
        }
        val durationMs = pcm.size.toLong() * 1000 / PcmTools.SAMPLE_RATE
        val id = soundDao.upsert(
            SoundEntity(
                title = title.take(120),
                artist = artist?.take(120),
                source = source,
                durationMs = durationMs,
                sampleRate = PcmTools.SAMPLE_RATE,
                channels = 1,
                bpm = AudioAnalysis.detectBpm(pcm, PcmTools.SAMPLE_RATE),
                key = MusicalKey.fromPitchClass(AudioAnalysis.detectKey(pcm, PcmTools.SAMPLE_RATE)).name,
                loudnessLufs = AudioAnalysis.loudnessLufs(pcm),
                addedAt = System.currentTimeMillis(),
                filePath = file.absolutePath,
                coverPath = coverPath,
                waveformJson = AudioAnalysis.waveform(pcm).toList().encodeWaveform(),
                tags = tags.joinToString("|"),
                fingerprint = fingerprint,
                importStatus = "READY",
            ),
        )
        val (silenceStart, silenceEnd) = AudioAnalysis.detectSilence(pcm, PcmTools.SAMPLE_RATE)
        File(applicationContext.filesDir, "trim_suggestions").apply { mkdirs() }
            .resolve("$id.json").writeText("{\"start\":$silenceStart,\"end\":$silenceEnd}")
        return Stored(id, title, duplicate = false, trimStart = silenceStart, trimEnd = silenceEnd)
    }

    private fun friendlyError(t: Throwable): String = when {
        t is IllegalArgumentException -> t.message ?: "Unsupported file"
        t is java.net.UnknownHostException -> "No internet connection"
        t is java.net.SocketTimeoutException -> "The connection timed out"
        t.message?.contains("HTTP 429") == true -> "TikTok is rate limiting. Try again in a minute."
        else -> t.message?.take(160) ?: "Import failed"
    }

    /** "1654632929123-@user-video.mp4" -> "@user-video". */
    private fun displayName(uri: String): String {
        val raw = uri.substringAfterLast('/').substringBefore('?')
        val unescaped = runCatching { android.net.Uri.decode(raw) }.getOrDefault(raw)
        return unescaped
            .replace(Regex("^\\d{8,}-"), "")
            .substringBeforeLast('.')
            .replace('_', ' ')
            .trim()
            .ifBlank { "Imported sound" }
    }

    private data class Stored(
        val soundId: Long,
        val title: String,
        val duplicate: Boolean,
        val trimStart: Long = 0,
        val trimEnd: Long = 0,
    )

    companion object {
        const val KEY_JOB_ID = "jobId"
        const val KEY_SOURCE_URI = "sourceUri"
        const val KEY_IS_LINK = "isLink"
        const val KEY_SOUND_ID = "soundId"
        const val KEY_DUPLICATE = "duplicate"
        const val KEY_TRIM_START = "trimStart"
        const val KEY_TRIM_END = "trimEnd"
    }
}