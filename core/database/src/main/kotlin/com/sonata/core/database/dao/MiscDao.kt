package com.sonata.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.sonata.core.database.entity.ImportJobEntity
import com.sonata.core.database.entity.MixEntity
import com.sonata.core.database.entity.PresetEntity
import com.sonata.core.database.entity.TransitionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MixDao {
    @Query("SELECT * FROM mixes")
    fun observeAll(): Flow<List<MixEntity>>

    @Query("SELECT * FROM mixes WHERE playlistId = :playlistId LIMIT 1")
    fun observeForPlaylist(playlistId: Long): Flow<MixEntity?>

    @Upsert
    suspend fun upsert(mix: MixEntity): Long

    @Query("DELETE FROM mixes WHERE id = :id")
    suspend fun delete(id: Long)

    @Upsert
    suspend fun upsertTransition(transition: TransitionEntity): Long

    @Query("SELECT * FROM transitions WHERE mixId = :mixId ORDER BY trackIndex")
    suspend fun transitionsFor(mixId: Long): List<TransitionEntity>
}

@Dao
interface PresetDao {
    @Query("SELECT * FROM presets ORDER BY category, name")
    fun observeAll(): Flow<List<PresetEntity>>

    @Query("SELECT * FROM presets WHERE id = :id")
    suspend fun getById(id: Long): PresetEntity?

    @Query("SELECT COUNT(*) FROM presets")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(preset: PresetEntity): Long

    @Query("DELETE FROM presets WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE presets SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)
}

@Dao
interface ImportJobDao {
    @Query("SELECT * FROM import_jobs ORDER BY rowid")
    fun observeQueue(): Flow<List<ImportJobEntity>>

    @Query("SELECT * FROM import_jobs WHERE id = :id")
    suspend fun get(id: String): ImportJobEntity?

    @Upsert
    suspend fun upsert(job: ImportJobEntity)

    @Query("DELETE FROM import_jobs WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM import_jobs WHERE stage = 'DONE' OR stage = 'FAILED'")
    suspend fun clearFinished()
}