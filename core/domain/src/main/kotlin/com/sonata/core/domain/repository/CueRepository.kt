package com.sonata.core.domain.repository

import com.sonata.core.domain.model.Cue
import com.sonata.core.domain.model.CueKind
import kotlinx.coroutines.flow.Flow

interface CueRepository {
    fun observe(soundId: Long): Flow<List<Cue>>
    suspend fun add(
        soundId: Long,
        positionMs: Long,
        label: String,
        kind: CueKind,
        endMs: Long? = null,
    ): Cue
    suspend fun ensure(soundId: Long, positionMs: Long, label: String, kind: CueKind)
    suspend fun delete(id: Long)
    suspend fun list(soundId: Long): List<Cue>
    suspend fun replaceAll(soundId: Long, cues: List<Cue>)
}