package com.sonata.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.sonata.core.database.entity.SoundEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SoundDao {
    @Query("SELECT * FROM sounds WHERE importStatus = 'READY' ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<SoundEntity>>

    @Query("SELECT * FROM sounds WHERE id = :id")
    fun observeById(id: Long): Flow<SoundEntity?>

    @Query("SELECT * FROM sounds WHERE id = :id")
    suspend fun getById(id: Long): SoundEntity?

    @Query(
        """
        SELECT s.* FROM sounds s
        JOIN sounds_fts f ON s.id = f.rowid
        WHERE sounds_fts MATCH :query AND s.importStatus = 'READY'
        ORDER BY s.playCount DESC LIMIT 100
        """,
    )
    fun search(query: String): Flow<List<SoundEntity>>

    @Query("SELECT * FROM sounds WHERE source = :source ORDER BY addedAt DESC")
    fun observeBySource(source: String): Flow<List<SoundEntity>>

    @Query("SELECT * FROM sounds WHERE fingerprint = :fp LIMIT 1")
    suspend fun findByFingerprint(fp: String): SoundEntity?

    @Upsert
    suspend fun upsert(sound: SoundEntity): Long

    @Query("DELETE FROM sounds WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("UPDATE sounds SET isFavorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    @Query("UPDATE sounds SET playCount = playCount + 1 WHERE id = :id")
    suspend fun incrementPlayCount(id: Long)

    @Query("UPDATE sounds SET loopCount = loopCount + 1 WHERE id = :id")
    suspend fun incrementLoopCount(id: Long)

    @Query("UPDATE sounds SET importStatus = :status WHERE id = :id")
    suspend fun setImportStatus(id: Long, status: String)

    @Query("UPDATE sounds SET title = :title, artist = :artist, album = :album WHERE id = :id")
    suspend fun updateMetadata(id: Long, title: String, artist: String?, album: String?)

    @Query("UPDATE sounds SET filePath = :filePath, activeVariant = :variant WHERE id = :id")
    suspend fun updateVariant(id: Long, filePath: String, variant: String)

    @Query("UPDATE sounds SET coverPath = :coverPath WHERE id = :id")
    suspend fun updateCover(id: Long, coverPath: String?)

    @Query(
        """
        UPDATE sounds SET
            filePath = :filePath,
            fullSongFilePath = :fullSongFilePath,
            clipFilePath = :clipFilePath,
            matchOffsetMs = :offsetMs,
            matchConfidence = :confidence,
            effectSpeed = :speed,
            activeVariant = 'FULL',
            durationMs = :durationMs,
            sampleRate = :sampleRate,
            channels = 1,
            bpm = :bpm,
            key = :key,
            tags = :tags,
            artist = COALESCE(:artist, artist),
            waveformJson = :waveformJson,
            loudnessLufs = :loudnessLufs,
            importStatus = 'READY'
        WHERE id = :id
        """
    )
    suspend fun updateFullSong(
        id: Long,
        filePath: String,
        fullSongFilePath: String,
        clipFilePath: String?,
        offsetMs: Long,
        confidence: Float,
        speed: Float,
        durationMs: Long,
        sampleRate: Int,
        bpm: Int,
        key: String,
        tags: String,
        artist: String?,
        waveformJson: String,
        loudnessLufs: Float,
    )

    @Query("SELECT DISTINCT tags FROM sounds WHERE tags != ''")
    suspend fun allTags(): List<String>

    @Query("SELECT * FROM sounds ORDER BY playCount DESC LIMIT :limit")
    suspend fun mostPlayed(limit: Int): List<SoundEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(sounds: List<SoundEntity>)
}
