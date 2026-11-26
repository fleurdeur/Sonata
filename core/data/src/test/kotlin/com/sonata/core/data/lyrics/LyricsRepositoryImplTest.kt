package com.sonata.core.data.lyrics

import com.sonata.core.data.repository.LyricsRepositoryImpl
import com.sonata.core.database.dao.LyricsDao
import com.sonata.core.database.entity.LyricsEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsRepositoryImplTest {

    private class FakeDao(var stored: LyricsEntity? = null) : LyricsDao {
        override fun observe(soundId: Long): Flow<LyricsEntity?> = flowOf(stored)
        override suspend fun get(soundId: Long): LyricsEntity? = stored
        override suspend fun upsert(lyrics: LyricsEntity) { stored = lyrics }
        override suspend fun delete(soundId: Long) { stored = null }
        override suspend fun updateOffset(soundId: Long, offsetMs: Long) {
            stored = stored?.copy(offsetMs = offsetMs)
        }
        override fun observeSoundIdsWithLyrics(): Flow<List<Long>> =
            flowOf(listOfNotNull(stored?.soundId))
    }

    private class FakeProvider(
        override val id: String,
        private val result: ProviderLyrics?,
    ) : LyricsProvider {
        override suspend fun fetch(query: LyricsQuery): ProviderLyrics? = result
    }

    @Test
    fun a_provider_hit_for_a_different_song_is_rejected() = runBlocking {
        val wrong = FakeProvider(
            "wrong",
            ProviderLyrics(
                provider = "wrong",
                plainText = "These are the wrong lyrics for a different song, with plenty of text.",
                trackName = "Completely Different Song",
                artistName = "Another Artist",
                confidence = 0.99f,
            ),
        )
        val right = FakeProvider(
            "right",
            ProviderLyrics(
                provider = "right",
                plainText = "These are the real lyrics for Midnight City by M83 and they are long enough.",
                trackName = "Midnight City",
                artistName = "M83",
                confidence = 0.50f,
            ),
        )
        val repo = LyricsRepositoryImpl(FakeDao(), setOf(wrong, right))
        val lyrics = repo.refresh(7, "Midnight City", "M83", 195_000, 1f)
        assertNotNull(lyrics)
        assertEquals("right", lyrics!!.provider)
    }

    @Test
    fun timed_lyrics_win_over_plain_lyrics() = runBlocking {
        val plain = FakeProvider(
            "plain",
            ProviderLyrics(
                provider = "plain",
                plainText = "A plain lyric sheet that is certainly long enough to be accepted.",
                trackName = "Midnight City",
                artistName = "M83",
                confidence = 0.95f,
            ),
        )
        val timed = FakeProvider(
            "timed",
            ProviderLyrics(
                provider = "timed",
                syncedLrc = "[00:01.00]First line\n[00:04.50]Second line\n[00:08.00]Third line",
                trackName = "Midnight City",
                artistName = "M83",
                confidence = 0.40f,
            ),
        )
        val repo = LyricsRepositoryImpl(FakeDao(), setOf(plain, timed))
        val lyrics = repo.refresh(8, "Midnight City", "M83", 195_000, 1f)
        assertNotNull(lyrics)
        assertEquals("timed", lyrics!!.provider)
        assertTrue(lyrics.synced)
        assertEquals(3, lyrics.lines.size)
    }
}