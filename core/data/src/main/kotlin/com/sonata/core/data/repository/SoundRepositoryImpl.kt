package com.sonata.core.data.repository

import com.sonata.core.common.di.IoDispatcher
import com.sonata.core.database.dao.SoundDao
import com.sonata.core.database.entity.decodeWaveform
import com.sonata.core.database.entity.SoundEntity
import com.sonata.core.database.entity.encodeWaveform
import com.sonata.core.domain.model.ImportStatus
import com.sonata.core.domain.model.MusicalKey
import com.sonata.core.domain.model.Sound
import com.sonata.core.domain.model.SoundSource
import com.sonata.core.domain.model.Tag
import com.sonata.core.domain.repository.SoundRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Singleton
class SoundRepositoryImpl @Inject constructor(
    private val dao: SoundDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) : SoundRepository {

    override fun observeAll(): Flow<List<Sound>> = dao.observeAll().map { it.map { e -> e.toModel() } }

    override fun observeSound(id: Long): Flow<Sound?> =
        dao.observeById(id).map { it?.toModel() }

    override fun search(query: String): Flow<List<Sound>> =
        dao.search(sanitizeFts(query)).map { it.map { e -> e.toModel() } }

    override fun observeTags(): Flow<List<Tag>> =
        observeAll().map { sounds ->
            sounds.flatMap { it.tags }.groupingBy { it }.eachCount()
                .map { (name, count) -> Tag(name, count) }
                .sortedByDescending { it.soundCount }
        }

    override suspend fun upsert(sound: Sound): Long = withContext(io) { dao.upsert(sound.toEntity()) }

    override suspend fun delete(ids: List<Long>) = withContext(io) { dao.deleteByIds(ids) }

    override suspend fun setFavorite(id: Long, favorite: Boolean) = withContext(io) { dao.setFavorite(id, favorite) }

    override suspend fun incrementPlayCount(id: Long) = withContext(io) { dao.incrementPlayCount(id) }

    override suspend fun incrementLoopCount(id: Long) = withContext(io) { dao.incrementLoopCount(id) }

    override suspend fun addTag(id: Long, tag: String) = withContext(io) {
        val entity = dao.getById(id) ?: return@withContext
        val current = entity.tags.split('|').filter { it.isNotBlank() }
        if (tag !in current) {
            dao.upsert(entity.copy(tags = (current + tag).joinToString("|")))
        }
    }

    override suspend fun updateMetadata(id: Long, title: String, artist: String?, album: String?) =
        withContext(io) { dao.updateMetadata(id, title, artist, album) }

    override suspend fun findByFingerprint(fingerprint: String): Sound? =
        withContext(io) { dao.findByFingerprint(fingerprint)?.toModel() }

    override suspend fun setImportStatus(id: Long, status: ImportStatus) =
        withContext(io) { dao.setImportStatus(id, status.name) }

    override fun observeForSource(source: SoundSource): Flow<List<Sound>> =
        dao.observeBySource(source.name).map { it.map { e -> e.toModel() } }

    private fun sanitizeFts(query: String): String =
        query.trim().split(' ').filter { it.isNotBlank() }.joinToString(" ") { "\"$it\"*" }

    private fun SoundEntity.toModel() = Sound(
        id = id, title = title, artist = artist, album = album,
        source = runCatching { SoundSource.valueOf(source) }.getOrDefault(SoundSource.LOCAL),
        durationMs = durationMs, sampleRate = sampleRate, channels = channels,
        bpm = bpm, key = key?.let { runCatching { MusicalKey.valueOf(it) }.getOrNull() },
        loudnessLufs = loudnessLufs, mood = mood, genre = genre,
        isFavorite = isFavorite, playCount = playCount, loopCount = loopCount,
        addedAt = addedAt, filePath = filePath, coverPath = coverPath,
        waveform = decodeWaveform(waveformJson).toFloatArray(),
        tags = tags.split('|').filter { it.isNotBlank() },
        fingerprint = fingerprint,
        importStatus = runCatching { ImportStatus.valueOf(importStatus) }.getOrDefault(ImportStatus.READY),
    )

    private fun Sound.toEntity() = SoundEntity(
        id = id, title = title, artist = artist, album = album, source = source.name,
        durationMs = durationMs, sampleRate = sampleRate, channels = channels,
        bpm = bpm, key = key?.name, loudnessLufs = loudnessLufs, mood = mood, genre = genre,
        isFavorite = isFavorite, playCount = playCount, loopCount = loopCount,
        addedAt = addedAt, filePath = filePath, coverPath = coverPath,
        waveformJson = waveform.toList().encodeWaveform(), tags = tags.joinToString("|"),
        fingerprint = fingerprint, importStatus = importStatus.name,
    )
}
