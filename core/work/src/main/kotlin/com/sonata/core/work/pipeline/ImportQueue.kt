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

/** Enqueues share-sheet, SAF, recorder and link imports on the visible queue. */
@Singleton
class ImportQueue @Inject constructor(
    @ApplicationContext private val context: Context,
    private val importRepository: ImportRepository,
) {
    suspend fun enqueue(sourceUri: String, isLink: Boolean, title: String = "") {
        val jobId = UUID.randomUUID().toString()
        importRepository.enqueue(ImportJob(jobId, ImportStage.QUEUED, 0f, title, sourceUri))
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (isLink) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED)
            .build()
        val request = OneTimeWorkRequestBuilder<ImportWorker>()
            .setInputData(
                workDataOf(
                    ImportWorker.KEY_JOB_ID to jobId,
                    ImportWorker.KEY_SOURCE_URI to sourceUri,
                    ImportWorker.KEY_IS_LINK to isLink,
                ),
            )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.SECONDS)
            .addTag("sonata-import")
            .addTag("import-job-" + jobId)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("import-$jobId", ExistingWorkPolicy.KEEP, request)
    }

    suspend fun cancel(jobId: String) {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork("import-$jobId")
            cancelAllWorkByTag("import-job-" + jobId)
        }
        importRepository.cancel(jobId)
    }

    /**
     * Starts a fresh attempt for a failed job. Download caches are keyed by
     * source identity, so completed network stages are reused.
     */
    suspend fun retry(job: ImportJob) {
        importRepository.cancel(job.id)
        enqueue(
            sourceUri = job.sourceUri,
            isLink = job.sourceUri.startsWith("http"),
            title = job.title,
        )
    }
}
