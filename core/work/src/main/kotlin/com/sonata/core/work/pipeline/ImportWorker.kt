package com.sonata.core.work.pipeline

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.sonata.core.common.di.IoDispatcher
import com.sonata.core.database.dao.SoundDao
import com.sonata.core.database.entity.decodeWaveform
import com.sonata.core.database.entity.SoundEntity
import com.sonata.core.database.entity.encodeWaveform
import com.sonata.core.domain.model.ImportJob
import com.sonata.core.domain.model.ImportStage
import com.sonata.core.domain.model.MusicalKey
import com.sonata.core.domain.repository.ImportRepository
import com.sonata.core.extractor.provider.LinkResolverRegistry
import com.sonata.core.work.analysis.AudioAnalysis
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat

/**
 * The measured import pipeline, run as a foreground WorkManager task with
 * exponential backoff: RESOLVE -> EXTRACT (remux, transcode only as fallback)
 * -> DEDUPE -> ANALYZE -> TRIM SUGGEST -> DONE.
 */
@HiltWorker
class ImportWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val resolvers: LinkResolverRegistry,
    private val importRepository: ImportRepository,
    private val soundDao: SoundDao,
    private val audioStore: AudioStore,
    @IoDispatcher private val io: CoroutineDispatcher,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(io) {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return@withContext Result.failure()
        val sourceUri = inputData.getString(KEY_SOURCE_URI) ?: return@withContext Result.failure()
        val isLink = inputData.getBoolean(KEY_IS_LINK, false)

        fun progress(stage: ImportStage, fraction: Float, title: String = "", error: String? = null) {
            kotlinx.coroutines.runBlocking {
                importRepository.update(
                    ImportJob(jobId, stage, fraction, title, sourceUri, error),
                )
            }
        }

        try {
            // 1. RESOLVE ------------------------------------------------------
            progress(ImportStage.RESOLVING, 0.05f)
            val audioInput: AudioInput = if (isLink) {
                val resolver = resolvers.forUrl(sourceUri)
                    ?: throw IllegalArgumentException("No resolver for this link")
                val resolved = resolver.resolve(sourceUri) { fraction, label ->
                    progress(ImportStage.DOWNLOADING, 0.1f + 0.4f * fraction, label)
                }
                AudioInput(resolved.title, resolved.audioUrl, isRemote = true)
            } else {
                val name = displayName(applicationContext, sourceUri) ?: "Sound"
                AudioInput(name, sourceUri, isRemote = false)
            }

            // 2. EXTRACT: lossless remux first, high-bitrate transcode only as
            //    a fallback (handled by AudioStore).
            progress(ImportStage.EXTRACTING, 0.55f, audioInput.title)
            val pcm = audioStore.extractToPcm(sourceUri)
            val outFile = if (isLink) audioStore.store(sourceUri) else audioStore.store(sourceUri)

            // 3. DEDUPE -------------------------------------------------------
            progress(ImportStage.ANALYZING, 0.7f, audioInput.title)
            val fingerprint = AudioAnalysis.fingerprint(pcm, 44100)
            val existing = soundDao.findByFingerprint(fingerprint)
            if (existing != null) {
                importRepository.update(
                    ImportJob(
                        jobId, ImportStage.DONE, 1f,
                        existing.title, sourceUri, soundId = existing.id,
                    ),
                )
                return@withContext Result.success(
                    workDataOf(KEY_SOUND_ID to existing.id, KEY_DUPLICATE to true),
                )
            }

            // 4. ANALYZE ------------------------------------------------------
            val bpm = AudioAnalysis.detectBpm(pcm, 44100)
            val keyIndex = AudioAnalysis.detectKey(pcm, 44100)
            val loudness = AudioAnalysis.loudnessLufs(pcm)
            val wave = AudioAnalysis.waveform(pcm)
            val (silenceStart, silenceEnd) = AudioAnalysis.detectSilence(pcm, 44100)

            // 5. STORE --------------------------------------------------------
            val durationMs = ((pcm.size.toLong() * 1000) / 44100)
            val id = soundDao.upsert(
                SoundEntity(
                    title = audioInput.title,
                    source = if (isLink) "LINK" else "SHARE",
                    durationMs = durationMs,
                    bpm = bpm,
                    key = MusicalKey.entries[keyIndex.coerceIn(0, 11)].name,
                    loudnessLufs = loudness,
                    addedAt = System.currentTimeMillis(),
                    filePath = outFile.absolutePath,
                    waveformJson = wave.toList().encodeWaveform(),
                    fingerprint = fingerprint,
                    importStatus = "READY",
                ),
            )
            // Persist suggested trim points for the editor's Auto-trim chip.
            File(applicationContext.filesDir, "trim_suggestions").apply { mkdirs() }
                .resolve("$id.json").writeText("{\"start\":$silenceStart,\"end\":$silenceEnd}")

            progress(ImportStage.DONE, 1f, audioInput.title)
            Result.success(
                workDataOf(
                    KEY_SOUND_ID to id,
                    KEY_DUPLICATE to false,
                    KEY_TRIM_START to silenceStart,
                    KEY_TRIM_END to silenceEnd,
                ),
            )
        } catch (t: Throwable) {
            progress(ImportStage.FAILED, 1f, error = t.message ?: "Import failed")
            if (runAttemptCount < 4) {
                Result.retry() // exponential backoff via the WorkManager policy
            } else {
                Result.failure()
            }
        }
    }

    private fun displayName(context: Context, uri: String): String? =
        runCatching {
            context.contentResolver.openInputStream(android.net.Uri.parse(uri))?.close()
            uri.substringAfterLast('/')
        }.getOrNull()

    data class AudioInput(val title: String, val uri: String, val isRemote: Boolean)

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
