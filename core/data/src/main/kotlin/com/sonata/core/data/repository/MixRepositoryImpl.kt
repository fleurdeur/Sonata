package com.sonata.core.data.repository

import com.sonata.core.common.di.IoDispatcher
import com.sonata.core.database.dao.MixDao
import com.sonata.core.database.dao.PlaylistDao
import com.sonata.core.database.dao.SoundDao
import com.sonata.core.database.entity.MixEntity
import com.sonata.core.database.entity.TransitionEntity
import com.sonata.core.domain.model.Mix
import com.sonata.core.domain.model.MusicalKey
import com.sonata.core.domain.model.TransitionSpec
import com.sonata.core.domain.repository.MixRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Singleton
class MixRepositoryImpl @Inject constructor(
    private val mixDao: MixDao,
    private val playlistDao: PlaylistDao,
    private val soundDao: com.sonata.core.database.dao.SoundDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) : MixRepository {

    override fun observeAll(): Flow<List<Mix>> = mixDao.observeAll().map { list ->
        list.map { m -> Mix(m.id, m.playlistId, mixDao.transitionsFor(m.id).map { it.toModel() }) }
    }

    override fun observeForPlaylist(playlistId: Long): Flow<Mix?> =
        mixDao.observeForPlaylist(playlistId).map { m ->
            m?.let { Mix(it.id, it.playlistId, mixDao.transitionsFor(it.id).map { t -> t.toModel() }) }
        }

    override suspend fun save(mix: Mix): Long = withContext(io) {
        val id = mixDao.upsert(MixEntity(id = mix.id, playlistId = mix.playlistId))
        mix.transitions.forEach { mixDao.upsertTransition(it.toEntity().copy(mixId = id)) }
        id
    }

    override suspend fun delete(mixId: Long) = withContext(io) { mixDao.delete(mixId) }

    override suspend fun upsertTransition(spec: TransitionSpec): Long =
        withContext(io) { mixDao.upsertTransition(spec.toEntity()) }

    /** Auto-order a playlist for the smoothest harmonic flow on the Camelot wheel. */
    override suspend fun autoOrder(playlistId: Long): List<Long> = withContext(io) {
        val sounds = playlistDao.getPlaylistSounds(playlistId)
        orderedCamelot(sounds).map { it.id }
    }

    private fun orderedCamelot(
        sounds: List<com.sonata.core.database.entity.SoundEntity>,
    ): List<com.sonata.core.database.entity.SoundEntity> {
        val camelotOf: (String) -> Int = { key ->
            runCatching { MusicalKey.valueOf(key).camelot }.getOrNull()
                ?.dropLast(1)?.toIntOrNull() ?: -1
        }
        val pool = sounds.toMutableList()
        val ordered = mutableListOf<com.sonata.core.database.entity.SoundEntity>()
        var current = pool.removeFirstOrNull() ?: return emptyList()
        ordered += current
        while (pool.isNotEmpty()) {
            val currentCamelot = current.key?.let(camelotOf) ?: -1
            val next = pool.minByOrNull { candidate ->
                val c = candidate.key?.let(camelotOf) ?: -1
                if (c == -1 || currentCamelot == -1) 12
                else minOf((c - currentCamelot + 12) % 12, (currentCamelot - c + 12) % 12)
                    .let { if (it == 0 || it == 1) 0 else it } // neighbors are perfect
            }
            ordered += next!!
            pool.remove(next)
            current = next
        }
        return ordered
    }

    private fun TransitionEntity.toModel() = TransitionSpec(
        id = id, mixId = mixId, trackIndex = trackIndex,
        type = runCatching {
            com.sonata.core.domain.model.TransitionType.valueOf(type)
        }.getOrDefault(com.sonata.core.domain.model.TransitionType.AUTO),
        lengthMs = lengthMs,
        curve = runCatching {
            com.sonata.core.domain.model.TransitionCurve.valueOf(curve)
        }.getOrDefault(com.sonata.core.domain.model.TransitionCurve.S_CURVE),
        intensity = intensity,
    )

    private fun TransitionSpec.toEntity() = TransitionEntity(
        id = id, mixId = mixId, trackIndex = trackIndex,
        type = type.name, lengthMs = lengthMs, curve = curve.name, intensity = intensity,
    )
}

