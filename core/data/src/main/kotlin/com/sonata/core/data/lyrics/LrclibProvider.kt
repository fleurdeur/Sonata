package com.sonata.core.data.lyrics

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * LRCLIB: an open, community-maintained database built for synced lyrics.
 * It is the primary provider because it returns LRC timestamps directly.
 */
@Singleton
class LrclibProvider @Inject constructor() : LyricsProvider {

    override val id = "LRCLIB"

    private val http = LyricsHttp()

    override suspend fun fetch(query: LyricsQuery): ProviderLyrics? {
        exact(query)?.let { return it }
        val search = LyricsHttp.encode(query.searchText)
        val array = http.getJson("https://lrclib.net/api/search?q=" + search) as? JsonArray ?: return null
        var best: ProviderLyrics? = null
        var bestScore = 0f
        for (element in array.take(25)) {
            val objectValue = element as? JsonObject ?: continue
            val candidate = parse(objectValue, 0f) ?: continue
            val score = LyricsMatch.score(
                query,
                candidate.trackName,
                candidate.artistName,
                durationMs = 0,
            )
            if (score > bestScore) {
                bestScore = score
                best = candidate.copy(confidence = score)
            }
        }
        return best?.takeIf { bestScore >= 0.52f }
    }

    private fun exact(query: LyricsQuery): ProviderLyrics? {
        val artist = query.cleanArtist ?: return null
        val url = "https://lrclib.net/api/get?track_name=" + LyricsHttp.encode(query.cleanTitle) +
            "&artist_name=" + LyricsHttp.encode(artist) +
            (if (query.durationMs > 0) "&duration=" + (query.durationMs / 1000) else "")
        val objectValue = http.getObject(url) ?: return null
        val candidate = parse(objectValue, 0.98f) ?: return null
        val score = LyricsMatch.score(query, candidate.trackName, candidate.artistName)
        return candidate.takeIf { score >= 0.50f }?.copy(confidence = maxOf(0.80f, score))
    }

    private fun parse(obj: JsonObject, confidence: Float): ProviderLyrics? {
        if (obj["instrumental"]?.jsonPrimitive?.contentOrNull == "true") return null
        val synced = obj["syncedLyrics"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val plain = obj["plainLyrics"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        if (synced == null && plain == null) return null
        return ProviderLyrics(
            provider = id,
            syncedLrc = synced,
            plainText = plain ?: synced?.let(LrcParser::plainFromLrc),
            trackName = obj["trackName"]?.jsonPrimitive?.contentOrNull
                ?: obj["name"]?.jsonPrimitive?.contentOrNull,
            artistName = obj["artistName"]?.jsonPrimitive?.contentOrNull,
            confidence = confidence,
        )
    }
}
