package com.sonata.core.extractor.bitchord

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Finds the original, full-length song on YouTube for a TikTok clip. Tries a
 * few query phrasings, stops at the first strong match, and never returns a
 * clip that is no longer than the source.
 */
@Singleton
class FullTrackFinder @Inject constructor(
    private val extractor: BitchordExtractor,
) {
    suspend fun find(
        title: String,
        artist: String?,
        clipDurationMs: Long,
    ): FullTrackCandidate? = withContext(Dispatchers.IO) {
        for (query in FullTrackMatch.queries(title, artist)) {
            val results = runCatching { extractor.search(query, music = false) }
                .getOrDefault(emptyList())
            val best = FullTrackMatch.best(results, title, artist, clipDurationMs)
            if (best != null && best.score >= 0.52f) return@withContext best
        }
        null
    }
}