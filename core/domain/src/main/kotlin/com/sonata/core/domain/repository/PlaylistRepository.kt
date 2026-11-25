package com.sonata.core.domain.repository

import com.sonata.core.domain.model.Playlist
import com.sonata.core.domain.model.SmartRules
import com.sonata.core.domain.model.Sound
import kotlinx.coroutines.flow.Flow

interface PlaylistRepository {
    fun observeAll(): Flow<List<Playlist>>
    fun observePlaylist(id: Long): Flow<Playlist?>
    fun observeSounds(playlistId: Long): Flow<List<Sound>>
    suspend fun create(name: String): Long
    suspend fun rename(id: Long, name: String)
    suspend fun setCover(id: Long, coverPath: String?)
    suspend fun delete(id: Long)
    suspend fun addSounds(playlistId: Long, soundIds: List<Long>)
    suspend fun removeSound(playlistId: Long, soundId: Long)
    suspend fun reorder(playlistId: Long, soundIds: List<Long>)
    suspend fun setSmartRules(id: Long, rules: SmartRules?)
}
