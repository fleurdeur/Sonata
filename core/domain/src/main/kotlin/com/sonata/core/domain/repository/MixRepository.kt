package com.sonata.core.domain.repository

import com.sonata.core.domain.model.Mix
import com.sonata.core.domain.model.TransitionSpec
import kotlinx.coroutines.flow.Flow

interface MixRepository {
    fun observeAll(): Flow<List<Mix>>
    fun observeForPlaylist(playlistId: Long): Flow<Mix?>
    suspend fun save(mix: Mix): Long
    suspend fun delete(mixId: Long)
    suspend fun upsertTransition(spec: TransitionSpec): Long
    suspend fun autoOrder(playlistId: Long): List<Long>
}
