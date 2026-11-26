package com.sonata.core.data.repository

import com.sonata.core.data.lyrics.LrcParser
import com.sonata.core.data.lyrics.LyricsMatch
import com.sonata.core.data.lyrics.LyricsProvider
import com.sonata.core.data.lyrics.LyricsQuery
import com.sonata.core.data.lyrics.ProviderLyrics
import com.sonata.core.database.dao.LyricsDao
import com.sonata.core.database.entity.LyricsEntity
import com.sonata.core.domain.model.LyricLine
import com.sonata.core.domain.model.Lyrics
import com.sonata.core.domain.repository.LyricsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.jvm.JvmSuppressWildcards
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Queries all providers in parallel, keeps the best timed match and falls back
 * to plain lyrics only when nothing timed exists. The result is cached in Room
 * so playing a song never hits the network twice.
 */
@Singleton
class LyricsRepositoryImpl @Inject constructor(
    private val dao: LyricsDao,
    private val providers: Set<@JvmSuppressWildcards LyricsProvider>,
) : LyricsRepository {

    override fun observe(soundId: Long): Flow<Lyrics?> =
        dao.observe(soundId).map { it?.toDomain() }

    override suspend fun get(soundId: Long): Lyrics? = withContext(Dispatchers.IO) {
        dao.get(soundId)?.toDomain()
    }

    override suspend fun getOrFetch(
        soundId: Long,
        title: String,
        artist: String?,
        durationMs: Long,
        speed: Float,
    ): Lyrics? = withContext(Dispatchers.IO) {
        val cached = dao.get(soundId)
        val missIsFresh = cached != null &&
            cached.provider == NONE &&
            System.currentTimeMillis() - cached.updatedAt < NONE_RETRY_MS
        if (cached != null && (cached.provider != NONE || missIsFresh)) {
            cached.toDomain()
        } else {
            fetchAndStore(soundId, title, artist, durationMs, speed)
        }
    }

    override suspend fun refresh(
        soundId: Long,
        title: String,
        artist: String?,
        durationMs: Long,
        speed: Float,
    ): Lyrics? = withContext(Dispatchers.IO) {
        fetchAndStore(soundId, title, artist, durationMs, speed)
    }

    override suspend fun setOffset(soundId: Long, offsetMs: Long) =
        withContext(Dispatchers.IO) {
            dao.updateOffset(soundId, offsetMs.coerceIn(-2_000L, 2_000L))
        }

    override fun observeSoundIdsWithLyrics(): Flow<Set<Long>> =
        dao.observeSoundIdsWithLyrics().map { it.toSet() }

    override suspend fun save(soundId: Long, lyrics: Lyrics) = withContext(Dispatchers.IO) {
        val lrc = lyrics.rawLrc ?: lyrics.lines.takeIf { it.isNotEmpty() }?.joinToString("\n") { line ->            val total = (line.timeMs + 59_999L).coerceAtLeast(0L)
            val minutes = total / 60_000L
            val seconds = (total % 60_000L) / 1000L
            val hundredths = (total % 1000L) / 10L
            "[" + minutes.toString().padStart(2, '0') + ":" +
                seconds.toString().padStart(2, '0') + "." +
                hundredths.toString().padStart(2, '0') + "]" + line.text
        }
        dao.upsert(
            LyricsEntity(
                soundId = soundId,
                provider = lyrics.provider.ifBlank { "Backup" },
                syncedLrc = lrc,
                plainText = lyrics.plainText.ifBlank { lrc?.let(LrcParser::plainFromLrc).orEmpty() },
                trackName = lyrics.trackName,
                artistName = lyrics.artistName,
                timeScale = lyrics.speed.coerceIn(0.2f, 5f),
                updatedAt = System.currentTimeMillis(),
                offsetMs = lyrics.offsetMs.coerceIn(-2_000L, 2_000L),
            ),
        )
    }

    private suspend fun fetchAndStore(
        soundId: Long,
        title: String,
        artist: String?,
        durationMs: Long,
        speed: Float,
    ): Lyrics? {
        val rate = if (speed.isFinite() && speed > 0.1f) speed else 1f
        val originalDuration = if (durationMs > 0 && rate != 1f) {
            (durationMs / rate).toLong()
        } else {
            durationMs
        }
        val query = LyricsQuery(title, artist, originalDuration)
        val candidates = coroutineScope {
            providers.map { provider ->
                async {
                    runCatching {
                        withTimeout(8_000) { provider.fetch(query) }
                    }.getOrNull()
                }
            }.awaitAll().filterNotNull()
        }

        val best = candidates.mapNotNull { candidate ->
            // A provider can return a search hit for a different song. When it
            // tells us the real title, insist that it is the same track.
            if (!candidate.trackName.isNullOrBlank() &&
                LyricsMatch.score(query, candidate.trackName, candidate.artistName) < 0.30f
            ) {
                return@mapNotNull null
            }
            val timed = candidate.syncedLrc?.let { LrcParser.parse(it, rate) }.orEmpty()
            val plain = candidate.plainText?.trim().orEmpty()
            if (timed.size < 2 && plain.length < 20) return@mapNotNull null
            Ranked(
                provider = candidate,
                lines = timed,
                plain = plain.ifBlank { LrcParser.plainFromLrc(candidate.syncedLrc.orEmpty()) },
                score = candidate.confidence + providerBonus(candidate.provider) + if (timed.size >= 2) 1.2f else 0f,
            )
        }.maxByOrNull { it.score }

        val previousOffset = dao.get(soundId)?.offsetMs ?: 0L
        val entity = if (best == null) {
            LyricsEntity(
                soundId = soundId,
                provider = NONE,
                timeScale = rate,
                updatedAt = System.currentTimeMillis(),
                offsetMs = previousOffset,
            )
        } else {
            LyricsEntity(
                soundId = soundId,
                provider = best.provider.provider,
                syncedLrc = best.provider.syncedLrc,
                plainText = best.plain,
                trackName = best.provider.trackName,
                artistName = best.provider.artistName,
                timeScale = rate,
                updatedAt = System.currentTimeMillis(),
                offsetMs = previousOffset,
            )
        }
        runCatching { dao.upsert(entity) }
        return entity.toDomain()
    }

    private fun providerBonus(name: String): Float = when (name) {
        "LRCLIB" -> 0.08f
        "NetEase" -> 0.05f
        "QQ Music" -> 0.04f
        else -> 0f
    }

    private fun LyricsEntity.toDomain(): Lyrics {
        val lines: List<LyricLine> = syncedLrc
            ?.let { LrcParser.parse(it, timeScale) }
            .orEmpty()
        val plain = plainText?.trim().orEmpty().ifBlank {
            syncedLrc?.let(LrcParser::plainFromLrc).orEmpty()
        }
        return Lyrics(
            soundId = soundId,
            provider = if (provider == NONE) "" else provider,
            lines = lines,
            plainText = plain,
            trackName = trackName,
            artistName = artistName,
            speed = timeScale,
            offsetMs = offsetMs,
            rawLrc = syncedLrc,
        )
    }

    private data class Ranked(
        val provider: ProviderLyrics,
        val lines: List<LyricLine>,
        val plain: String,
        val score: Float,
    )

    private companion object {
        const val NONE = "none"
        const val NONE_RETRY_MS = 6 * 60 * 60 * 1000L
    }
}