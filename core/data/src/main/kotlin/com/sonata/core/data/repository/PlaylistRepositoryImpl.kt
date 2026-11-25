package com.sonata.core.data.repository

import com.sonata.core.common.di.IoDispatcher
import com.sonata.core.database.dao.PlaylistDao
import com.sonata.core.database.dao.SoundDao
import com.sonata.core.database.entity.decodeWaveform
import com.sonata.core.database.entity.PlaylistEntity
import com.sonata.core.database.entity.PlaylistSoundCrossRef
import com.sonata.core.domain.model.Playlist
import com.sonata.core.domain.model.SmartRules
import com.sonata.core.domain.model.Sound
import com.sonata.core.domain.repository.PlaylistRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

@Singleton
class PlaylistRepositoryImpl @Inject constructor(
    private val playlistDao: PlaylistDao,
    private val soundDao: SoundDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) : PlaylistRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override fun observeAll(): Flow<List<Playlist>> = playlistDao.observeAll().map { it.map { e -> e.toModel() } }

    override fun observePlaylist(id: Long): Flow<Playlist?> =
        playlistDao.observeById(id).map { it?.toModel() }

    override fun observeSounds(playlistId: Long): Flow<List<Sound>> =
        playlistDao.observePlaylistSounds(playlistId).map { entities ->
            entities.map { e ->
                Sound(
                    id = e.id, title = e.title, artist = e.artist, album = e.album,
                    durationMs = e.durationMs, bpm = e.bpm, isFavorite = e.isFavorite,
                    playCount = e.playCount, loopCount = e.loopCount, addedAt = e.addedAt,
                    filePath = e.filePath, coverPath = e.coverPath,
                    waveform = decodeWaveform(e.waveformJson).toFloatArray(),
                    tags = e.tags.split('|').filter { it.isNotBlank() },
                )
            }
        }

    override suspend fun create(name: String): Long =
        withContext(io) { playlistDao.upsert(PlaylistEntity(name = name, createdAt = System.currentTimeMillis())) }

    override suspend fun rename(id: Long, name: String) {
        withContext(io) { playlistDao.getById(id)?.let { playlistDao.upsert(it.copy(name = name)) } }
    }

    override suspend fun setCover(id: Long, coverPath: String?) {
        withContext(io) { playlistDao.getById(id)?.let { playlistDao.upsert(it.copy(coverPath = coverPath)) } }
    }

    override suspend fun delete(id: Long) = withContext(io) {
        playlistDao.delete(id)
        playlistDao.clearSounds(id)
    }

    override suspend fun addSounds(playlistId: Long, soundIds: List<Long>) = withContext(io) {
        playlistDao.addSounds(soundIds.mapIndexed { i, s -> PlaylistSoundCrossRef(playlistId, s, i) })
    }

    override suspend fun removeSound(playlistId: Long, soundId: Long) = withContext(io) {
        playlistDao.removeSound(playlistId, soundId)
    }

    override suspend fun reorder(playlistId: Long, soundIds: List<Long>) =
        withContext(io) { playlistDao.setSounds(playlistId, soundIds) }

    override suspend fun setSmartRules(id: Long, rules: SmartRules?) {
        withContext(io) {
            playlistDao.getById(id)?.let {
                playlistDao.upsert(
                    it.copy(smartRulesJson = rules?.let { r -> json.encodeToString(r) }),
                )
            }
        }
    }

    private fun PlaylistEntity.toModel() = Playlist(
        id = id, name = name, coverPath = coverPath, coverPreset = coverPreset,
        createdAt = createdAt,
        rules = smartRulesJson?.let { runCatching { json.decodeFromString<SmartRules>(it) }.getOrNull() },
    )
}
