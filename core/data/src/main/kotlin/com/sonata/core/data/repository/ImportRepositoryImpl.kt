package com.sonata.core.data.repository

import com.sonata.core.common.di.IoDispatcher
import com.sonata.core.database.dao.ImportJobDao
import com.sonata.core.database.entity.ImportJobEntity
import com.sonata.core.domain.model.ImportFailureKind
import com.sonata.core.domain.model.ImportJob
import com.sonata.core.domain.model.ImportStage
import com.sonata.core.domain.repository.ImportRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Singleton
class ImportRepositoryImpl @Inject constructor(
    private val dao: ImportJobDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ImportRepository {

    override fun observeQueue(): Flow<List<ImportJob>> =
        dao.observeQueue().map { list -> list.map { it.toModel() } }

    override suspend fun enqueue(job: ImportJob) = withContext(io) { dao.upsert(job.toEntity()) }

    override suspend fun update(job: ImportJob) = withContext(io) { dao.upsert(job.toEntity()) }

    override suspend fun get(jobId: String): ImportJob? = withContext(io) {
        dao.get(jobId)?.toModel()
    }

    override suspend fun report(
        jobId: String,
        stage: ImportStage,
        progress: Float,
        title: String?,
        error: String?,
        failure: ImportFailureKind?,
        diagnostics: String?,
        attempt: Int?,
        fullSongVerified: Boolean?,
        matchConfidence: Float?,
    ) = withContext(io) {
        val existing = dao.get(jobId) ?: return@withContext
        dao.upsert(
            existing.copy(
                stage = stage.name,
                progress = progress.coerceIn(0f, 1f),
                title = title ?: existing.title,
                error = error,
                failure = failure?.name,
                diagnostics = diagnostics?.take(MAX_DIAGNOSTICS) ?: existing.diagnostics,
                attempt = attempt ?: existing.attempt,
                fullSongVerified = fullSongVerified ?: existing.fullSongVerified,
                matchConfidence = matchConfidence ?: existing.matchConfidence,
            ),
        )
    }

    override suspend fun cancel(jobId: String) = withContext(io) { dao.delete(jobId) }

    override suspend fun clearFinished() = withContext(io) { dao.clearFinished() }

    private fun ImportJobEntity.toModel() = ImportJob(
        id = id,
        stage = ImportStage.fromStored(stage),
        progress = progress,
        title = title,
        sourceUri = sourceUri,
        error = error,
        soundId = soundId,
        failure = failure?.let { name -> runCatching { ImportFailureKind.valueOf(name) }.getOrNull() },
        diagnostics = diagnostics,
        attempt = attempt,
        fullSongVerified = fullSongVerified,
        matchConfidence = matchConfidence,
    )

    private fun ImportJob.toEntity() = ImportJobEntity(
        id = id,
        stage = stage.name,
        progress = progress,
        title = title,
        sourceUri = sourceUri,
        error = error,
        soundId = soundId,
        failure = failure?.name,
        diagnostics = diagnostics,
        attempt = attempt,
        fullSongVerified = fullSongVerified,
        matchConfidence = matchConfidence,
    )

    private companion object {
        const val MAX_DIAGNOSTICS = 1_500
    }
}
