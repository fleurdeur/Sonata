package com.sonata.core.data.repository

import com.sonata.core.common.di.IoDispatcher
import com.sonata.core.database.dao.ImportJobDao
import com.sonata.core.database.entity.ImportJobEntity
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

    override suspend fun cancel(jobId: String) = withContext(io) { dao.delete(jobId) }

    override suspend fun clearFinished() = withContext(io) { dao.clearFinished() }

    private fun ImportJobEntity.toModel() = ImportJob(
        id = id,
        stage = runCatching { ImportStage.valueOf(stage) }.getOrDefault(ImportStage.QUEUED),
        progress = progress,
        title = title,
        sourceUri = sourceUri,
        error = error,
        soundId = soundId,
    )

    private fun ImportJob.toEntity() = ImportJobEntity(
        id = id, stage = stage.name, progress = progress,
        title = title, sourceUri = sourceUri, error = error, soundId = soundId,
    )
}
