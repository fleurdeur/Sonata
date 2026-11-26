package com.sonata.core.extractor.bitchord

import com.sonata.core.domain.model.SearchResult
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The full YouTube / YouTube Music extractor, BitChord style: search with
 * suggestions, home feed, radio/autoplay via the `next` endpoint, stream
 * resolution with multi-client fallback and auto-refresh of expired URLs.
 */
@Singleton
class BitchordExtractor @Inject constructor() {

    private val http = BitchordHttp()

    // ---------------------------------------------------------------- search

    suspend fun search(query: String, music: Boolean = false): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val client = if (music) InnerTube.ANDROID_MUSIC else InnerTube.ANDROID
            val response = http.post(
                InnerTube.SEARCH_ENDPOINT,
                client,
                InnerTube.body(
                    client,
                    params = if (music) SEARCH_PARAM_MUSIC else SEARCH_PARAM_VIDEOS,
                    query = query,
                ),
            )
            parseSearch(response, client.clientName)
        }

    suspend fun searchSuggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        val client = InnerTube.WEB_REMIX
        val response = runCatching {
            http.post(
                InnerTube.SUGGESTIONS_ENDPOINT,
                client,
                kotlinx.serialization.json.buildJsonObject {
                    put("query", kotlinx.serialization.json.JsonPrimitive(query))
                    put("context", clientContext(client))
                },
            )
        }.getOrNull() ?: return@withContext emptyList()
        response.deepFind("suggestion")
            .mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
    }

    // ------------------------------------------------------------- playback

    /** Resolve a playable stream, walking the full client fallback chain. */
    suspend fun resolveStream(videoId: String): ResolveOutcome = withContext(Dispatchers.IO) {
        var outcome: ResolveOutcome = ResolveOutcome.Unavailable(videoId, "No playable stream from any client")
        var restricted = false
        var regionBlocked = false
        for (client in InnerTube.fallbackOrder) {
            val response = runCatching {
                http.post(InnerTube.PLAYER_ENDPOINT, client, InnerTube.body(client, videoId))
            }.getOrNull() ?: continue

            val status = response.deepFindFirst("playabilityStatus")?.jsonObject
            val reason = status?.get("reason")?.let {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.content
            }
            val playability = status?.get("status")?.let {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.content
            }
            if (playability == "LOGIN_REQUIRED") {
                // One client asking for a login does not mean the track is
                // age-restricted; another client often still returns a URL.
                restricted = true
                continue
            }
            if (playability == "UNPLAYABLE") {
                if (reason?.contains("country", ignoreCase = true) == true) {
                    regionBlocked = true
                }
                outcome = ResolveOutcome.Unavailable(videoId, reason ?: "Unplayable")
                continue
            }

            val stream = InnerTube.bestAudioUrl(response.jsonObject)
            if (stream != null) {
                return@withContext ResolveOutcome.Ok(stream.url, stream.bitrate, client)
            }
        }
        when {
            restricted -> ResolveOutcome.AgeRestricted(videoId)
            regionBlocked -> ResolveOutcome.RegionBlocked(videoId)
            else -> outcome
        }
    }

    /** Radio / autoplay: up-next songs for a video via the `next` endpoint. */
    suspend fun radio(videoId: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val client = InnerTube.ANDROID_MUSIC
        val response = runCatching {
            http.post(InnerTube.NEXT_ENDPOINT, client, InnerTube.body(client, videoId))
        }.getOrNull() ?: return@withContext emptyList()
        response.deepFind("playlistPanelVideoRenderer")
            .mapNotNull { renderer ->
                (renderer as? kotlinx.serialization.json.JsonObject)?.let { parseRenderer(it, client.clientName) }
            }
    }

    /** Re-resolves automatically when a cached URL has expired. */
    suspend fun refreshIfNeeded(videoId: String, cachedUrl: String): String? =
        if (InnerTube.isExpired(cachedUrl)) {
            (resolveStream(videoId) as? ResolveOutcome.Ok)?.url
        } else {
            cachedUrl
        }

    // -------------------------------------------------------------- parsing

    internal fun parseSearch(
        response: kotlinx.serialization.json.JsonElement,
        clientName: String,
    ): List<SearchResult> =
        // The Android search client returns compactVideoRenderer instead of the
        // web client's videoRenderer. Accept every shape YouTube uses.
        listOf("videoRenderer", "compactVideoRenderer", "videoWithContextRenderer")
            .flatMap { field -> response.deepFind(field) }
            .mapNotNull { el ->
                (el as? kotlinx.serialization.json.JsonObject)?.let { parseRenderer(it, clientName) }
            }
            .distinctBy { it.id }

    private fun parseRenderer(renderer: kotlinx.serialization.json.JsonObject, clientName: String): SearchResult? {
        val id = renderer["videoId"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: return null
        val title = renderer["title"]
            ?.let { runsOrText(it) } ?: ""
        val author = renderer["ownerText"]?.let { runsOrText(it) }
            ?: renderer["longBylineText"]?.let { runsOrText(it) }
        val lengthText = renderer["lengthText"]?.let { runsOrText(it) }
        val duration = lengthText?.let(::parseClock)
        val thumbnail = renderer.deepFindFirst("thumbnail")?.jsonObject
            ?.deepFindFirst("url")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        return SearchResult(id, title, author, duration ?: 0, thumbnail, clientName)
    }

    private fun runsOrText(element: kotlinx.serialization.json.JsonElement): String? = runCatching {
        val obj = element.jsonObject
        (obj["simpleText"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: obj["runs"]?.jsonArray?.joinToString("") {
                (it.jsonObject["text"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
            }
    }.getOrNull()

    private fun parseClock(text: String): Long {
        val parts = text.split(':').map { it.trim().toLongOrNull() ?: 0 }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            1 -> parts[0]
            else -> 0
        } * 1000
    }

    companion object {
        const val SEARCH_PARAM_VIDEOS = "EgIQAQ%3D%3D"
        const val SEARCH_PARAM_MUSIC = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"
    }
}

// ------------------------------------------------------------------ helpers

private fun kotlinx.serialization.json.JsonElement.deepFind(field: String): List<kotlinx.serialization.json.JsonElement> {
    val out = mutableListOf<kotlinx.serialization.json.JsonElement>()
    fun walk(element: kotlinx.serialization.json.JsonElement) {
        when (element) {
            is kotlinx.serialization.json.JsonObject -> {
                element.entries.forEach { (k, v) ->
                    if (k == field) out += v
                    walk(v)
                }
            }
            is kotlinx.serialization.json.JsonArray -> element.forEach { walk(it) }
            else -> Unit
        }
    }
    walk(this)
    return out
}

private fun kotlinx.serialization.json.JsonElement.deepFindFirst(field: String) = deepFind(field).firstOrNull()

private fun json(vararg pairs: Pair<String, String>): kotlinx.serialization.json.JsonObject =
    kotlinx.serialization.json.buildJsonObject {
        pairs.forEach { (k, v) -> put(k, kotlinx.serialization.json.JsonPrimitive(v)) }
    }

private fun clientContext(client: InnerTube.ClientContext) =
    kotlinx.serialization.json.JsonObject(
        mapOf(
            "clientName" to kotlinx.serialization.json.JsonPrimitive(client.clientName),
            "clientVersion" to kotlinx.serialization.json.JsonPrimitive(client.clientVersion),
        ),
    )

/** Plain HttpURLConnection — zero extra dependencies, keeps the APK tiny. */
class BitchordHttp {
    fun post(
        endpoint: String,
        client: InnerTube.ClientContext,
        body: kotlinx.serialization.json.JsonObject,
    ): kotlinx.serialization.json.JsonElement {
        val url = URL("https://www.youtube.com/$endpoint?prettyPrint=false")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("User-Agent", client.userAgent)
        connection.setRequestProperty("X-YouTube-Client-Name", client.clientId)
        connection.setRequestProperty("X-YouTube-Client-Version", client.clientVersion)
        connection.setRequestProperty("X-Goog-Api-Key", client.apiKey)
        connection.setRequestProperty("Origin", "https://www.youtube.com")
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.doOutput = true
        connection.outputStream.use { it.write(body.toString().toByteArray()) }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IllegalStateException("InnerTube " + code + ": " + text.take(200))
            return InnerTube.json.parseToJsonElement(text)
        } finally {
            connection.disconnect()
        }
    }
}