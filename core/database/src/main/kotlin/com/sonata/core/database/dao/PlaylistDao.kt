package com.sonata.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.sonata.core.database.entity.PlaylistEntity
import com.sonata.core.database.entity.PlaylistSoundCrossRef
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun observeById(id: Long): Flow<PlaylistEntity?>

    @Query(
        """
        SELECT s.* FROM sounds s
        JOIN playlist_sounds p ON s.id = p.soundId
        WHERE p.playlistId = :playlistId
        ORDER BY p.position
        """,
    )
    fun observePlaylistSounds(playlistId: Long): Flow<List<com.sonata.core.database.entity.SoundEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getById(id: Long): PlaylistEntity?

    @Query(
        """
        SELECT s.* FROM sounds s
        JOIN playlist_sounds p ON s.id = p.soundId
        WHERE p.playlistId = :playlistId
        ORDER BY p.position
        """,
    )
    suspend fun getPlaylistSounds(playlistId: Long): List<com.sonata.core.database.entity.SoundEntity>

    @Upsert
    suspend fun upsert(playlist: PlaylistEntity): Long

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM playlist_sounds WHERE playlistId = :playlistId")
    suspend fun clearSounds(playlistId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addSounds(refs: List<PlaylistSoundCrossRef>)

    @Transaction
    suspend fun setSounds(playlistId: Long, soundIds: List<Long>) {
        clearSounds(playlistId)
        addSounds(soundIds.mapIndexed { i, s -> PlaylistSoundCrossRef(playlistId, s, i) })
    }

    @Query("DELETE FROM playlist_sounds WHERE playlistId = :playlistId AND soundId = :soundId")
    suspend fun removeSound(playlistId: Long, soundId: Long)
}
