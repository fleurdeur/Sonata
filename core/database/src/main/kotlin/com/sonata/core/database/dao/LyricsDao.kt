package com.sonata.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.sonata.core.database.entity.LyricsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LyricsDao {
    @Query("SELECT * FROM lyrics WHERE soundId = :soundId")
    fun observe(soundId: Long): Flow<LyricsEntity?>

    @Query("SELECT * FROM lyrics WHERE soundId = :soundId")
    suspend fun get(soundId: Long): LyricsEntity?

    @Upsert
    suspend fun upsert(lyrics: LyricsEntity)

    @Query("DELETE FROM lyrics WHERE soundId = :soundId")
    suspend fun delete(soundId: Long)

    @Query("UPDATE lyrics SET offsetMs = :offsetMs WHERE soundId = :soundId")
    suspend fun updateOffset(soundId: Long, offsetMs: Long)

    /** Sound ids with a cached lyric sheet, for library smart filters. */
    @Query("SELECT soundId FROM lyrics WHERE provider != 'none'")
    fun observeSoundIdsWithLyrics(): Flow<List<Long>>
}
