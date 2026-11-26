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
import com.sonata.core.domain.model.ImportFailureKind
import com.sonata.core.domain.model.ImportJob
import com.sonata.core.domain.model.ImportStage
import com.sonata.core.domain.model.MusicalKey
import com.sonata.core.domain.repository.ImportRepository
import com.sonata.core.domain.repository.LyricsRepository
import com.sonata.core.extractor.provider.LinkResolverRegistry
import com.sonata.core.extractor.tiktok.MediaDownloader
import com.sonata.core.extractor.tiktok.TikTokUrls
import com.sonata.core.work.analysis.AudioAnalysis
import com.sonata.core.work.tiktok.TikTokPipeline
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import javax.net.ssl.SSLException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * One measured import pipeline for every entry point:
 *
 * FETCH_SOURCE -> EXTRACT_AUDIO -> IDENTIFY_SONG -> FETCH_FULL_TRACK ->
 * ALIGN_EDIT -> ENCODE_AAC -> PERSIST_LIBRARY -> FETCH_LYRICS -> DONE.
 *
 * Network downloads are atomic and cached by source identity, so a bounded
 * WorkManager retry resumes from the last completed stage instead of fetching
 * everything again. Failures carry a typed reason and a readable diagnostic.
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
    private val lyricsRepository: LyricsRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(io) {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return@withContext Result.failure()
        val sourceUri = inputData.getString(KEY_SOURCE_URI) ?: return@withContext Result.failure()
        val isLink = inputData.getBoolean(KEY_IS_LINK, false)
        val storedJob = importRepository.get(jobId)
        var currentStage = storedJob?.stage?.takeUnless { it.terminal } ?: ImportStage.QUEUED
        var currentTitle = storedJob?.title.orEmpty()
        val attempt = runAttemptCount + 1

        suspend fun progress(
            stage: ImportStage,
            fraction: Float,
            title: String = "",
            error: String? = null,
            failure: ImportFailureKind? = null,
            diagnostics: String? = null,
            verified: Boolean? = null,
            confidence: Float? = null,
        ) {
            if (title.isNotBlank()) currentTitle = title
            currentStage = stage
            runCatching {
                importRepository.report(
                    jobId = jobId,
                    stage = stage,
                    progress = fraction,
                    title = currentTitle,
                    error = error,
                    failure = failure,
                    diagnostics = diagnostics,
                    attempt = attempt,
                    fullSongVerified = verified,
                    matchConfidence = confidence,
                )
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
            val failure = classify(t, currentStage)
            val detail = diagnosticsFor(t, currentStage, attempt)
            android.util.Log.e("SonataImport", "import failed at " + currentStage.name, t)
            if (failure.retryable && attempt < MAX_ATTEMPTS) {
                // Keep the stage visible: WorkManager retries with backoff and
                // cached sources let the next attempt skip completed downloads.
                progress(
                    currentStage,
                    0f,
                    error = "Retrying automatically (" + attempt + " of " + (MAX_ATTEMPTS - 1) +
                        "): " + friendlyError(t),
                    failure = failure,
                    diagnostics = detail,
                )
                Result.retry()
            } else {
                progress(
                    ImportStage.FAILED,
                    1f,
                    error = friendlyError(t),
                    failure = failure,
                    diagnostics = detail,
                )
                Result.failure()
            }
        }
    }

    // ------------------------------------------------------------- TikTok

    private suspend fun importTikTok(
        url: String,
        progress: suspend (
            ImportStage,
            Float,
            String,
            String?,
            ImportFailureKind?,
            String?,
            Boolean?,
            Float?,
        ) -> Unit,
    ): Stored {
        val track = tiktokPipeline.process(url) { stage, fraction, title ->
            progress(stage, fraction, title, null, null, null, null, null)
        }
        progress(
            ImportStage.ENCODE_AAC,
            0.90f,
            "Encoding " + track.title,
            null,
            null,
            track.diagnostics,
            track.fromFullSong,
            track.matchConfidence,
        )
        val file = audioStore.newSoundFile("m4a")
        val written = PcmFileWriter.write(track.pcm, PcmTools.SAMPLE_RATE, file)
        val stored = persist(
            pcm = track.pcm,
            file = written,
            title = track.title,
            artist = track.artist,
            source = "TIKTOK",
            coverPath = track.coverPath,
            tags = track.tags,
            speed = track.effect.speed,
            fetchLyrics = track.fromFullSong,
            verified = track.fromFullSong,
            confidence = track.matchConfidence,
            progress = progress,
        )
        // The import is safely in the library; only now discard source caches.
        track.cacheFiles.forEach { runCatching { it.delete() } }
        return stored
    }

    // -------------------------------------------------------------- links

    private suspend fun importLink(
        url: String,
        progress: suspend (
            ImportStage,
            Float,
            String,
            String?,
            ImportFailureKind?,
            String?,
            Boolean?,
            Float?,
        ) -> Unit,
    ): Stored {
        progress(ImportStage.FETCH_SOURCE, 0.04f, "Resolving link", null, null, null, null, null)
        val resolver = resolvers.forUrl(url)
            ?: throw ImportFailureException(ImportFailureKind.SOURCE_UNAVAILABLE, "No importer handles this link")
        val resolved = resolver.resolve(url) { fraction, label ->
            runBlocking {
                progress(
                    ImportStage.FETCH_SOURCE,
                    0.06f + fraction * 0.30f,
                    label,
                    null,
                    null,
                    null,
                    null,
                    null,
                )
            }
        }
        val cache = audioStore.cacheFile("link_" + stableName(url) + ".media")
        progress(ImportStage.FETCH_SOURCE, 0.40f, "Saving audio", null, null, null, null, null)
        try {
            if (!cache.isFile || cache.length() < 1_024L) {
                downloader.download(resolved.audioUrl, cache) { fraction ->
                    runBlocking {
                        progress(
                            ImportStage.FETCH_SOURCE,
                            0.40f + fraction * 0.12f,
                            "Saving audio",
                            null,
                            null,
                            null,
                            null,
                            null,
                        )
                    }
                }
            }
            val pcm = decode(cache.absolutePath, progress, 0.56f)
            val title = resolved.title.ifBlank { "Imported sound" }
            val stored = finishPcm(
                pcm = pcm,
                title = title,
                artist = resolved.author,
                source = "LINK",
                coverPath = null,
                tags = listOf("link"),
                progress = progress,
            )
            cache.delete()
            return stored
        } catch (t: Throwable) {
            // Keep the cache on failure so the next attempt resumes download.
            throw t
        }
    }

    // -------------------------------------------------------------- local

    private suspend fun importLocal(
        uri: String,
        progress: suspend (
            ImportStage,
            Float,
            String,
            String?,
            ImportFailureKind?,
            String?,
            Boolean?,
            Float?,
        ) -> Unit,
    ): Stored {
        progress(ImportStage.FETCH_SOURCE, 0.05f, "Reading file", null, null, null, null, null)
        val info = audioStore.info(uri)
        val pcm = decode(uri, progress, 0.20f)
        val title = displayName(uri)
        return if (info.hasVideo) {
            finishPcm(
                pcm = pcm,
                title = title,
                artist = null,
                source = "SHARE",
                coverPath = null,
                tags = listOf("shared"),
                progress = progress,
            )
        } else {
            val stored = audioStore.store(uri)
            persist(
                pcm = pcm,
                file = stored,
                title = title,
                artist = null,
                source = "SHARE",
                coverPath = null,
                tags = listOf("shared"),
                progress = progress,
            )
        }
    }

    // ------------------------------------------------------------- shared

    private suspend fun finishPcm(
        pcm: ShortArray,
        title: String,
        artist: String?,
        source: String,
        coverPath: String?,
        tags: List<String>,
        progress: suspend (
            ImportStage,
            Float,
            String,
            String?,
            ImportFailureKind?,
            String?,
            Boolean?,
            Float?,
        ) -> Unit,
    ): Stored {
        progress(ImportStage.ENCODE_AAC, 0.80f, "Encoding audio", null, null, null, null, null)
        val file = audioStore.newSoundFile("m4a")
        val written = PcmFileWriter.write(pcm, PcmTools.SAMPLE_RATE, file)
        return persist(
            pcm = pcm,
            file = written,
            title = title,
            artist = artist,
            source = source,
            coverPath = coverPath,
            tags = tags,
            progress = progress,
        )
    }

    private suspend fun decode(
        path: String,
        progress: suspend (
            ImportStage,
            Float,
            String,
            String?,
            ImportFailureKind?,
            String?,
            Boolean?,
            Float?,
        ) -> Unit,
        fraction: Float,
    ): ShortArray {
        progress(ImportStage.EXTRACT_AUDIO, fraction, "Extracting audio", null, null, null, null, null)
        return try {
            audioStore.extractToPcm(path)
        } catch (t: Throwable) {
            throw if (t.message?.contains("truncated", ignoreCase = true) == true) {
                ImportFailureException(ImportFailureKind.TRUNCATED_AUDIO, "The source audio stopped early", t)
            } else {
                ImportFailureException(ImportFailureKind.EXTRACTION_FAILED, "Could not decode this audio", t)
            }
        }
    }

    private suspend fun persist(
        pcm: ShortArray,
        file: File,
        title: String,
        artist: String?,
        source: String,
        coverPath: String?,
        tags: List<String>,
        progress: suspend (
            ImportStage,
            Float,
            String,
            String?,
            ImportFailureKind?,
            String?,
            Boolean?,
            Float?,
        ) -> Unit,
        speed: Float = 1f,
        fetchLyrics: Boolean = true,
        verified: Boolean? = null,
        confidence: Float? = null,
    ): Stored {
        progress(ImportStage.PERSIST_LIBRARY, 0.94f, title, null, null, null, verified, confidence)
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
        val (silenceStart, silenceEnd) = runCatching {
            AudioAnalysis.detectSilence(pcm, PcmTools.SAMPLE_RATE)
        }.getOrDefault(0L to durationMs)
        runCatching {
            File(applicationContext.filesDir, "trim_suggestions").apply { mkdirs() }
                .resolve(id.toString() + ".json")
                .writeText("{\"start\":" + silenceStart + ",\"end\":" + silenceEnd + "}")
        }
        if (fetchLyrics && durationMs >= 30_000) {
            progress(ImportStage.FETCH_LYRICS, 0.97f, "Downloading lyrics", null, null, null, verified, confidence)
            runCatching {
                lyricsRepository.getOrFetch(id, title, artist, durationMs, speed)
            }
        }
        return Stored(id, title, duplicate = false, trimStart = silenceStart, trimEnd = silenceEnd)
    }

    private fun classify(t: Throwable, stage: ImportStage): ImportFailureKind {
        if (t is ImportFailureException) return t.kind
        val message = t.message.orEmpty()
        return when {
            t is UnknownHostException || t is ConnectException || t is SocketTimeoutException ||
                t is SSLException || t is IOException && !message.contains("No space", true) ->
                ImportFailureKind.NETWORK
            message.contains("HTTP 429") || message.contains("rate limit", true) ->
                ImportFailureKind.RATE_LIMITED
            message.contains("HTTP 404") || message.contains("HTTP 410") ->
                ImportFailureKind.SOURCE_UNAVAILABLE
            message.contains("truncated", true) || message.contains("incomplete", true) ->
                ImportFailureKind.TRUNCATED_AUDIO
            message.contains("no audio", true) -> ImportFailureKind.NO_AUDIO
            message.contains("no space", true) || t is OutOfMemoryError ->
                ImportFailureKind.STORAGE
            stage == ImportStage.EXTRACT_AUDIO -> ImportFailureKind.EXTRACTION_FAILED
            else -> ImportFailureKind.UNKNOWN
        }
    }

    private fun friendlyError(t: Throwable): String {
        val kind = runCatching { classify(t, ImportStage.QUEUED) }.getOrDefault(ImportFailureKind.UNKNOWN)
        return when (kind) {
            ImportFailureKind.NETWORK -> "No internet connection. Your download cache is safe; retry when online."
            ImportFailureKind.RATE_LIMITED -> "The source is rate limiting requests. Try again in a minute."
            ImportFailureKind.SOURCE_UNAVAILABLE -> t.message?.take(160) ?: "This source is unavailable."
            ImportFailureKind.NO_AUDIO -> "This source has no audible audio track."
            ImportFailureKind.EXTRACTION_FAILED -> "The audio could not be decoded. Try another file."
            ImportFailureKind.TRUNCATED_AUDIO -> "The audio ended early. Retry to finish the download."
            ImportFailureKind.NO_RELIABLE_MATCH -> "No reliable full song was found; the real clip was kept."
            ImportFailureKind.LOW_CONFIDENCE_MATCH -> "The full song match was not confident; the real clip was kept."
            ImportFailureKind.STORAGE -> "There is not enough free storage for this import."
            ImportFailureKind.CANCELLED -> "Import cancelled."
            ImportFailureKind.UNKNOWN -> t.message?.take(160)?.takeIf { it.isNotBlank() } ?: "Import failed."
        }
    }

    /** Local-only diagnostics; absolute paths and device names are stripped. */
    private fun diagnosticsFor(t: Throwable, stage: ImportStage, attempt: Int): String {
        val raw = (t::class.simpleName ?: "Error") + ": " + (t.message ?: "no message")
        val safe = raw
            .replace(Regex("""[A-Za-z]:\\[^\s"']+"""), "<local path>")
            .replace(Regex("""/Users/[^\s"']+"""), "<local path>")
            .replace(Regex("""/home/[^\s"']+"""), "<local path>")
            .take(900)
        return "stage=" + stage.name + " attempt=" + attempt + " " + safe
    }

    private fun stableName(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
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
        private const val MAX_ATTEMPTS = 3
    }
}
