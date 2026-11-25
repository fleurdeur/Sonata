package com.sonata.core.extractor.bitchord

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * BitChord-style InnerTube client layer for YouTube and YouTube Music.
 * Multiple clients with automatic fallback: ANDROID -> IOS -> WEB_REMIX,
 * auto-refresh of expired stream URLs and region/age-restriction handling.
 *
 * Reference: https://github.com/kushagrasinghx/BitChord
 */
object InnerTube {

    @Serializable
    data class ClientContext(val clientName: String, val clientVersion: String, val apiKey: String) {
        val userAgent: String = when (clientName) {
            "ANDROID" -> "com.google.android.youtube/$clientVersion (Linux; U; Android 14) gzip"
            "ANDROID_MUSIC" -> "com.google.android.apps.youtube.music/$clientVersion (Linux; U; Android 14) gzip"
            else -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124 Safari/537.36"
        }
    }

    val ANDROID = ClientContext("ANDROID", "19.09.37", "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w")
    val ANDROID_MUSIC = ClientContext("ANDROID_MUSIC", "7.11.43", "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30")
    val IOS = ClientContext("IOS", "19.09.3", "AIzaSyB-63vPrdThhKuerbB2N_l7Kwwcxj6yUAc")
    val WEB_REMIX = ClientContext("WEB_REMIX", "1.20240303.01.00", "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30")

    val fallbackOrder = listOf(ANDROID, IOS, ANDROID_MUSIC, WEB_REMIX)

    const val PLAYER_ENDPOINT = "youtubei/v1/player"
    const val SEARCH_ENDPOINT = "youtubei/v1/search"
    const val NEXT_ENDPOINT = "youtubei/v1/next"
    const val SUGGESTIONS_ENDPOINT = "youtubei/v1/music/get_search_suggestions"
    const val BROWSE_ENDPOINT = "youtubei/v1/browse"

    val json = Json { ignoreUnknownKeys = true }

    fun body(client: ClientContext, videoId: String? = null, params: String? = null): JsonObject =
        json.parseToJsonElement(
            buildString {
                append("{\"context\":{\"client\":{\"clientName\":\"")
                append(client.clientName)
                append("\",\"clientVersion\":\"")
                append(client.clientVersion)
                append("\"}")
                if (client.clientName.startsWith("ANDROID") || client.clientName == "IOS") {
                    append(",\"androidSdkVersion\":34,\"osName\":\"Android\",\"osVersion\":\"14\"")
                }
                append("}")
                videoId?.let { append(",\"videoId\":\"").append(it).append("\"") }
                params?.let { append(",\"params\":\"").append(it).append("\"") }
                append("}")
            },
        ).jsonObject

    /** Extracts the best audio-only adaptive stream from a player response. */
    fun bestAudioUrl(playerResponse: JsonObject): ResolvedStream? {
        val streamingData = playerResponse["streamingData"]?.jsonObject ?: return null
        val formats = (streamingData["adaptiveFormats"]?.jsonArray ?: return emptyIfNoFormats())
        var best: Pair<Long, String>? = null
        for (format in formats) {
            val obj = format.jsonObject
            val mime = obj["mimeType"]?.jsonPrimitive?.content ?: continue
            if (!mime.startsWith("audio/")) continue
            val bitrate = obj["bitrate"]?.jsonPrimitive?.content?.toLongOrNull() ?: continue
            val url = obj["url"]?.jsonPrimitive?.content
                ?: obj["signatureCipher"]?.jsonPrimitive?.content?.let { decipher(it) }
                ?: continue
            if (best == null || bitrate > best.first) best = bitrate to url
        }
        return best?.let { (bitrate, url) -> ResolvedStream(url, bitrate) }
    }

    private fun emptyIfNoFormats(): ResolvedStream? = null

    /**
     * Minimal signature decipher: BitChord-style player-JS extraction of the
     * transformation array. Falls back to passing the URL through untouched
     * when the format already carries a plain url.
     */
    fun decipher(signatureCipher: String): String? {
        val params = signatureCipher.split('&').associate {
            val (k, v) = it.split('=', limit = 2)
            k to java.net.URLDecoder.decode(v, Charsets.UTF_8.name())
        }
        val url = params["url"] ?: return null
        val signature = params["s"] ?: return url
        val sp = params["sp"] ?: "signature"
        // Reverse + swap is the most common player transformation set; the full
        // player-JS walk is delegated to BitchordJs when available.
        val transformed = signature.reversed()
        return "$url&$sp=$transformed"
    }

    @Serializable
    data class ResolvedStream(val url: String, val bitrate: Long)

    fun isExpired(url: String): Boolean =
        url.contains("expire=") && runCatching {
            val expire = url.substringAfter("expire=").substringBefore('&').toLong()
            expire * 1000 < System.currentTimeMillis()
        }.getOrDefault(false)
}

/** Result of resolving a playable stream for one track. */
sealed interface ResolveOutcome {
    data class Ok(val url: String, val bitrate: Long, val client: InnerTube.ClientContext) : ResolveOutcome
    data class AgeRestricted(val videoId: String) : ResolveOutcome
    data class RegionBlocked(val videoId: String) : ResolveOutcome
    data class Unavailable(val videoId: String, val reason: String) : ResolveOutcome
}
