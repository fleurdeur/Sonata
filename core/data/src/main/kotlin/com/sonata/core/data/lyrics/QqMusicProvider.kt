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

/** QQ Music: another large timed-lyrics catalogue, including Chinese songs. */
@Singleton
class QqMusicProvider @Inject constructor() : LyricsProvider {

    override val id = "QQ Music"

    private val http = LyricsHttp()

    override suspend fun fetch(query: LyricsQuery): ProviderLyrics? {
        val search = LyricsHttp.encode(query.searchText)
        val searchUrl = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp" +
            "?w=" + search + "&format=json&n=8&cr=1&new_json=1"
        val root = http.getObject(searchUrl, referer = "https://y.qq.com/") ?: return null
        val songs = root["data"]?.jsonObject?.get("song")?.jsonObject?.get("list")?.jsonArray
            ?: return null
        val ranked = songs.mapNotNull { element ->
            val song = element as? JsonObject ?: return@mapNotNull null
            val mid = song["mid"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val title = song["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val singer = song["singer"]?.jsonArray?.joinToString(", ") {
                it.jsonObject["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            }.orEmpty()
            val interval = song["interval"]?.jsonPrimitive?.longOrNull ?: 0L
            val score = LyricsMatch.score(query, title, singer, interval * 1000)
            if (score < 0.48f) return@mapNotNull null
            RankedSong(mid, title, singer, score)
        }.sortedByDescending { it.score }.take(3)

        for (candidate in ranked) {
            val lyricUrl = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg" +
                "?songmid=" + LyricsHttp.encode(candidate.mid) + "&format=json&nobase64=1"
            val obj = http.getObject(lyricUrl, referer = "https://y.qq.com/") ?: continue
            val lrc = obj["lyric"]?.jsonPrimitive?.contentOrNull
                ?.decodeEntities()
                ?.takeIf { it.contains('[') && it.contains(':') }
                ?: continue
            return ProviderLyrics(
                provider = "QQ Music",
                syncedLrc = lrc,
                plainText = LrcParser.plainFromLrc(lrc),
                trackName = candidate.name,
                artistName = candidate.artist,
                confidence = candidate.score,
            )
        }
        return null
    }

    private fun String.decodeEntities(): String = this
        .replace("&apos;", "'")
        .replace("&#39;", "'")
        .replace("&quot;", "\"")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")

    private data class RankedSong(
        val mid: String,
        val name: String,
        val artist: String,
        val score: Float,
    )
}