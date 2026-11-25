package com.sonata.core.domain.repository

import com.sonata.core.domain.model.Preset
import kotlinx.coroutines.flow.Flow

interface PresetRepository {
    fun observeAll(): Flow<List<Preset>>
    fun observeFavorites(): Flow<List<Preset>>
    suspend fun upsert(preset: Preset): Long
    suspend fun delete(id: Long)
    suspend fun rename(id: Long, name: String)
    suspend fun duplicate(id: Long): Long
    suspend fun seedIfEmpty()
}
