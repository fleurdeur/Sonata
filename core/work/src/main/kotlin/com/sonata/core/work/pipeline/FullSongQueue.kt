package com.sonata.core.work.pipeline

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.sonata.core.domain.model.ImportJob
import com.sonata.core.domain.model.ImportStage
import com.sonata.core.domain.repository.ImportRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adds a user-chosen full recording to an existing clip. This is the visible
 * fallback when automatic identification is not confident: the candidate still
 * has to pass audio proof in [ImportWorker] before it replaces the clip.
 */
@Singleton
class FullSongQueue @Inject constructor(
    @ApplicationContext private val context: Context,
    private val importRepository: ImportRepository,
) {
    suspend fun attach(
        soundId: Long,
        parentJobId: String?,
        videoId: String,
        title: String,
        artist: String?,
    ) {
        val jobId = UUID.randomUUID().toString()
        val label = title.take(80).ifBlank { "selected recording" }
        importRepository.enqueue(
            ImportJob(
                id = jobId,
                stage = ImportStage.QUEUED,
                progress = 0f,
                title = "Adding full song · $label",
                sourceUri = "https://www.youtube.com/watch?v=$videoId",
                soundId = soundId,
            ),
        )
        val request = OneTimeWorkRequestBuilder<ImportWorker>()
            .setInputData(
                workDataOf(
                    ImportWorker.KEY_JOB_ID to jobId,
                    ImportWorker.KEY_SOURCE_URI to "https://www.youtube.com/watch?v=$videoId",
                    ImportWorker.KEY_IS_LINK to false,
                    ImportWorker.KEY_ATTACH_SOUND_ID to soundId,
                    ImportWorker.KEY_ATTACH_VIDEO_ID to videoId,
                    ImportWorker.KEY_ATTACH_ARTIST to artist,
                    ImportWorker.KEY_PARENT_JOB_ID to parentJobId,
                ),
            )
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.SECONDS)
            .addTag("sonata-import")
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("attach-$soundId", ExistingWorkPolicy.REPLACE, request)
    }

    suspend fun retry(job: ImportJob) {
        val videoId = videoId(job.sourceUri) ?: return
        attach(
            soundId = job.soundId ?: return,
            parentJobId = null,
            videoId = videoId,
            title = job.title.removePrefix("Adding full song · "),
            artist = null,
        )
    }

    private fun videoId(url: String): String? =
        Regex("(?:v=|youtu\\.be/)([A-Za-z0-9_-]{11})").find(url)?.groupValues?.getOrNull(1)
}
