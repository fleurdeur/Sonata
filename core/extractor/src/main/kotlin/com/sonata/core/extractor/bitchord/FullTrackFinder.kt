package com.sonata.core.extractor.bitchord

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

/**
 * Finds the original, full-length song on YouTube for a TikTok clip.
 *
 * Every query is searched concurrently, all results are ranked together, and
 * the caller receives several candidates. Trying only the first text match was
 * the reason a wrong upload could block a correct one that sat one row lower.
 */
@Singleton
class FullTrackFinder @Inject constructor(
    private val extractor: BitchordExtractor,
) {
    suspend fun findCandidates(
        title: String,
        artist: String?,
        clipDurationMs: Long,
        limit: Int = 4,
    ): List<FullTrackCandidate> = withContext(Dispatchers.IO) {
        val queries = FullTrackMatch.queries(title, artist)
        if (queries.isEmpty()) return@withContext emptyList()
        val pages = supervisorScope {
            queries.map { query ->
                async {
                    runCatching { extractor.search(query, music = false) }
                        .getOrDefault(emptyList())
                }
            }.awaitAll()
        }
        FullTrackMatch.rank(
            results = pages.flatten().distinctBy { it.id },
            title = title,
            artist = artist,
            clipDurationMs = clipDurationMs,
            limit = limit,
        )
    }

    suspend fun find(
        title: String,
        artist: String?,
        clipDurationMs: Long,
    ): FullTrackCandidate? = findCandidates(title, artist, clipDurationMs, 1).firstOrNull()
}