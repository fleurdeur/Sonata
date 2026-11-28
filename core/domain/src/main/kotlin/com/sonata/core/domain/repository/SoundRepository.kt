package com.sonata.core.domain.repository

import com.sonata.core.domain.model.ImportStatus
import com.sonata.core.domain.model.PlaybackVariant
import com.sonata.core.domain.model.Sound
import com.sonata.core.domain.model.SoundSource
import com.sonata.core.domain.model.Tag
import kotlinx.coroutines.flow.Flow

interface SoundRepository {
    fun observeAll(): Flow<List<Sound>>
    fun observeSound(id: Long): Flow<Sound?>
    suspend fun get(id: Long): Sound?
    fun search(query: String): Flow<List<Sound>>
    fun observeTags(): Flow<List<Tag>>
    suspend fun upsert(sound: Sound): Long
    suspend fun delete(ids: List<Long>)
    suspend fun setFavorite(id: Long, favorite: Boolean)
    suspend fun incrementPlayCount(id: Long)
    suspend fun incrementLoopCount(id: Long)
    suspend fun addTag(id: Long, tag: String)
    suspend fun updateMetadata(id: Long, title: String, artist: String?, album: String?)
    suspend fun updateCover(id: Long, coverPath: String?)
    suspend fun findByFingerprint(fingerprint: String): Sound?
    suspend fun setImportStatus(id: Long, status: ImportStatus)
    suspend fun setVariant(id: Long, variant: PlaybackVariant): Sound?
    fun observeForSource(source: SoundSource): Flow<List<Sound>>
}
