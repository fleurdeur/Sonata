package com.sonata.core.data.lyrics

import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import kotlin.math.abs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** What the providers need to identify a song. */
data class LyricsQuery(
    val title: String,
    val artist: String?,
    val durationMs: Long,
) {
    val cleanTitle: String get() = LyricsMatch.clean(title)
    val cleanArtist: String? get() = LyricsMatch.cleanArtist(artist)
    val searchText: String
        get() {
            val title = cleanTitle
            val who = cleanArtist
            return if (who != null && !title.contains(who, ignoreCase = true)) {
                who + " " + title
            } else {
                title
            }
        }
}

/** A candidate returned by one provider. */
data class ProviderLyrics(
    val provider: String,
    val syncedLrc: String? = null,
    val plainText: String? = null,
    val trackName: String? = null,
    val artistName: String? = null,
    val confidence: Float = 0f,
)

interface LyricsProvider {
    val id: String
    suspend fun fetch(query: LyricsQuery): ProviderLyrics?
}

/** Shared title/artist/duration matching and HTTP for the lyrics providers. */
object LyricsMatch {

    private val noise = listOf(
        "sped up", "speed up", "slowed down", "slowed", "nightcore", "reverb",
        "bass boosted", "8d audio", "tiktok", "lyrics", "lyric video",
        "official audio", "official video", "official music video", "audio",
    )

    fun clean(raw: String): String {
        var value = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        value = value.replace(Regex("""[\(\[\{][^)\]\}]{0,40}[\)\]\}]"""), " ")
        value = value.replace(Regex("""[#@]\S+"""), " ")
        value = value.replace(Regex("""[^\p{L}\p{N}\s'&+-]"""), " ")
        var lower = value.lowercase()
        noise.forEach { lower = lower.replace(it, " ") }
        return lower.replace(Regex("""\s+"""), " ").trim()
    }

    fun cleanArtist(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return raw.substringBefore(" - Topic")
            .substringBefore("VEVO")
            .replace(Regex("""[^\p{L}\p{N}\s'&.-]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .takeIf { it.isNotBlank() }
    }

    fun normalized(raw: String): String =
        Normalizer.normalize(raw, Normalizer.Form.NFKC)
            .lowercase()
            .replace(Regex("""[^\p{L}\p{N}\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    fun score(
        query: LyricsQuery,
        trackName: String?,
        artistName: String?,
        durationMs: Long = 0,
    ): Float {
        val title = normalized(query.cleanTitle)
        val candidateTitle = normalized(trackName.orEmpty())
        if (title.isBlank() || candidateTitle.isBlank()) return 0f

        val titleWords = title.split(' ').filter { it.length > 1 }.toSet()
        if (titleWords.isEmpty()) return 0f
        val candidateWords = candidateTitle.split(' ').filter { it.length > 1 }.toSet()
        val overlap = titleWords.count { word ->
            candidateWords.any { it == word || (word.length >= 4 && it.contains(word)) }
        }.toFloat() / titleWords.size

        var value = overlap * 0.70f
        val artist = normalized(query.cleanArtist.orEmpty())
        val candidateArtist = normalized(artistName.orEmpty())
        if (artist.isNotBlank()) {
            val artistWords = artist.split(' ').filter { it.length > 1 }
            if (artistWords.any { candidateArtist.contains(it) || candidateTitle.contains(it) }) {
                value += 0.18f
            }
        } else {
            value += 0.05f
        }
        if (durationMs > 0 && query.durationMs > 0) {
            val delta = abs(durationMs - query.durationMs).toFloat() / query.durationMs
            value += (0.12f * (1f - delta.coerceIn(0f, 1f)))
        }
        return value.coerceIn(0f, 1f)
    }
}

/** Tiny JSON HTTP client: no OkHttp, no extra dependency in the APK. */
class LyricsHttp {

    private val json = Json { ignoreUnknownKeys = true }

    fun get(url: String, referer: String? = null, userAgent: String = AGENT): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 6_000
        connection.readTimeout = 8_000
        connection.setRequestProperty("User-Agent", userAgent)
        connection.setRequestProperty("Accept", "application/json, text/plain, */*")
        connection.setRequestProperty("Accept-Language", "en-US,en;q=0.8")
        referer?.let { connection.setRequestProperty("Referer", it) }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) return null
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val builder = StringBuilder()
                val buffer = CharArray(8_192)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    builder.append(buffer, 0, read)
                    if (builder.length > 2_000_000) break
                }
                builder.toString()
            }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    fun getJson(url: String, referer: String? = null): JsonElement? =
        get(url, referer)?.let { body ->
            runCatching { json.parseToJsonElement(stripJsonp(body)) }.getOrNull()
        }

    fun getObject(url: String, referer: String? = null): JsonObject? =
        getJson(url, referer)?.let { runCatching { it.jsonObject }.getOrNull() }

    private fun stripJsonp(body: String): String {
        val trimmed = body.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return trimmed
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        return if (start >= 0 && end > start) trimmed.substring(start, end + 1) else trimmed
    }

    companion object {
        const val AGENT = "Sonata/1.3 (Android; lyrics; https://github.com/fleurdeur/Sonata)"
        fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
    }
}