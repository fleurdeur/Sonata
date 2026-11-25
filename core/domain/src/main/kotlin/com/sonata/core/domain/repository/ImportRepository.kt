package com.sonata.core.domain.repository

import com.sonata.core.domain.model.ImportJob
import kotlinx.coroutines.flow.Flow

interface ImportRepository {
    fun observeQueue(): Flow<List<ImportJob>>
    suspend fun enqueue(job: ImportJob)
    suspend fun update(job: ImportJob)
    suspend fun cancel(jobId: String)
    suspend fun clearFinished()
}
