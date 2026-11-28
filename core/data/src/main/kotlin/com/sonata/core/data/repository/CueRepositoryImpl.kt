package com.sonata.core.data.repository

import com.sonata.core.common.di.IoDispatcher
import com.sonata.core.database.dao.CueDao
import com.sonata.core.database.entity.CueEntity
import com.sonata.core.domain.model.Cue
import com.sonata.core.domain.model.CueKind
import com.sonata.core.domain.repository.CueRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Singleton
class CueRepositoryImpl @Inject constructor(
    private val dao: CueDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) : CueRepository {

    override fun observe(soundId: Long): Flow<List<Cue>> =
        dao.observeForSound(soundId).map { list -> list.map { it.toModel() } }

    override suspend fun add(
        soundId: Long,
        positionMs: Long,
        label: String,
        kind: CueKind,
        endMs: Long?,
    ): Cue = withContext(io) {
        val entity = CueEntity(
            soundId = soundId,
            positionMs = positionMs.coerceAtLeast(0L),
            endMs = endMs,
            label = label.take(60),
            kind = kind.name,
            createdAt = System.currentTimeMillis(),
        )
        val id = dao.upsert(entity)
        entity.copy(id = id).toModel()
    }

    override suspend fun ensure(
        soundId: Long,
        positionMs: Long,
        label: String,
        kind: CueKind,
    ) = withContext(io) {
        if (dao.findByLabel(soundId, label) == null) {
            dao.upsert(
                CueEntity(
                    soundId = soundId,
                    positionMs = positionMs.coerceAtLeast(0L),
                    label = label,
                    kind = kind.name,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    override suspend fun delete(id: Long) = withContext(io) { dao.delete(id) }

    override suspend fun list(soundId: Long): List<Cue> = withContext(io) {
        dao.listForSound(soundId).map { it.toModel() }
    }

    override suspend fun replaceAll(soundId: Long, cues: List<Cue>) = withContext(io) {
        dao.deleteForSound(soundId)
        cues.forEach { cue ->
            dao.upsert(
                CueEntity(
                    soundId = soundId,
                    positionMs = cue.positionMs.coerceAtLeast(0L),
                    endMs = cue.endMs,
                    label = cue.label.take(60),
                    kind = cue.kind.name,
                    createdAt = cue.createdAt,
                ),
            )
        }
    }

    private fun CueEntity.toModel() = Cue(
        id = id,
        soundId = soundId,
        positionMs = positionMs,
        endMs = endMs,
        label = label,
        kind = runCatching { CueKind.valueOf(kind) }.getOrDefault(CueKind.MEMORY),
        createdAt = createdAt,
    )
}