package com.sonata.core.domain.repository

import com.sonata.core.domain.model.ImportFailureKind
import com.sonata.core.domain.model.ImportJob
import com.sonata.core.domain.model.ImportStage
import kotlinx.coroutines.flow.Flow

interface ImportRepository {
    fun observeQueue(): Flow<List<ImportJob>>
    suspend fun enqueue(job: ImportJob)
    suspend fun update(job: ImportJob)
    suspend fun get(jobId: String): ImportJob?

    /**
     * Advances only the fields owned by the worker. Keeping this as a targeted
     * write means concurrent UI reads never lose match diagnostics or attempt
     * counters.
     */
    suspend fun report(
        jobId: String,
        stage: ImportStage,
        progress: Float,
        title: String? = null,
        error: String? = null,
        failure: ImportFailureKind? = null,
        diagnostics: String? = null,
        attempt: Int? = null,
        fullSongVerified: Boolean? = null,
        matchConfidence: Float? = null,
        soundId: Long? = null,
    )

    suspend fun cancel(jobId: String)
    suspend fun clearFinished()
}
