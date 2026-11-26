package com.sonata.core.domain.repository

import com.sonata.core.domain.model.Lyrics
import kotlinx.coroutines.flow.Flow

interface LyricsRepository {
    /** Cached lyrics, if any. Emits null until the first fetch finishes. */
    fun observe(soundId: Long): Flow<Lyrics?>

    suspend fun get(soundId: Long): Lyrics?

    /**
     * Returns cached lyrics, or queries every provider once and caches the
     * best match. [speed] is the tempo of the finished track relative to the
     * original recording, so timestamps can be shifted into the right timeline.
     */
    suspend fun getOrFetch(
        soundId: Long,
        title: String,
        artist: String?,
        durationMs: Long,
        speed: Float = 1f,
    ): Lyrics?

    /** Ignores the cache and asks the providers again. */
    suspend fun refresh(
        soundId: Long,
        title: String,
        artist: String?,
        durationMs: Long,
        speed: Float = 1f,
    ): Lyrics?

    /** Saves the user's per-track timing calibration. */
    suspend fun setOffset(soundId: Long, offsetMs: Long)

    /** Restores a lyric sheet from a library backup without hitting providers. */
    suspend fun save(soundId: Long, lyrics: Lyrics)

    fun observeSoundIdsWithLyrics(): Flow<Set<Long>>
}
