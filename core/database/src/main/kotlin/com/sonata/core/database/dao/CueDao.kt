package com.sonata.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.sonata.core.database.entity.CueEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CueDao {
    @Query("SELECT * FROM cues WHERE soundId = :soundId ORDER BY positionMs ASC, id ASC")
    fun observeForSound(soundId: Long): Flow<List<CueEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(cue: CueEntity): Long

    @Query("DELETE FROM cues WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM cues WHERE soundId = :soundId AND label = :label LIMIT 1")
    suspend fun findByLabel(soundId: Long, label: String): CueEntity?

    @Query("SELECT * FROM cues WHERE soundId = :soundId ORDER BY positionMs ASC, id ASC")
    suspend fun listForSound(soundId: Long): List<CueEntity>

    @Query("DELETE FROM cues WHERE soundId = :soundId")
    suspend fun deleteForSound(soundId: Long)
}