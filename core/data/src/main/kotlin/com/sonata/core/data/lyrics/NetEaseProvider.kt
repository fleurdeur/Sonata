package com.sonata.core.data.lyrics

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** NetEase Cloud Music: strong Chinese catalogue and timed LRC. */
@Singleton
class NetEaseProvider @Inject constructor() : LyricsProvider {

    override val id = "NetEase"

    private val http = LyricsHttp()

    override suspend fun fetch(query: LyricsQuery): ProviderLyrics? {
        val search = LyricsHttp.encode(query.searchText)
        val searchUrl = "https://music.163.com/api/search/get?s=" + search + "&type=1&limit=8&offset=0"
        val root = http.getObject(searchUrl, referer = "https://music.163.com/") ?: return null
        val songs = root["result"]?.jsonObject?.get("songs")?.jsonArray ?: return null
        val ranked = songs.mapNotNull { element ->
            val song = element as? JsonObject ?: return@mapNotNull null
            val name = song["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val artists = song["artists"]?.jsonArray?.joinToString(", ") {
                it.jsonObject["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            }.orEmpty()
            val duration = song["duration"]?.jsonPrimitive?.longOrNull ?: 0L
            val score = LyricsMatch.score(query, name, artists, duration)
            if (score < 0.48f) return@mapNotNull null
            RankedSong(song["id"]?.jsonPrimitive?.longOrNull, name, artists, score)
        }.sortedByDescending { it.score }.take(3)

        for (candidate in ranked) {
            val id = candidate.id ?: continue
            val lyricUrl = "https://music.163.com/api/song/lyric?id=" + id + "&lv=1&kv=1&tv=-1"
            val lyric = http.getObject(lyricUrl, referer = "https://music.163.com/") ?: continue
            val lrc = lyric["lrc"]?.jsonObject?.get("lyric")?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.contains('[') && it.contains(':') }
                ?: continue
            return ProviderLyrics(
                provider = "NetEase",
                syncedLrc = lrc,
                plainText = LrcParser.plainFromLrc(lrc),
                trackName = candidate.name,
                artistName = candidate.artist,
                confidence = candidate.score,
            )
        }
        return null
    }

    private data class RankedSong(
        val id: Long?,
        val name: String,
        val artist: String,
        val score: Float,
    )
}