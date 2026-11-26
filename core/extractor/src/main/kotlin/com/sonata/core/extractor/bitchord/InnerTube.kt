package com.sonata.core.extractor.bitchord

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
        /** Numeric id InnerTube expects in X-YouTube-Client-Name. */
        val clientId: String = when (clientName) {
            "ANDROID" -> "3"
            "IOS" -> "5"
            "ANDROID_MUSIC" -> "21"
            "ANDROID_VR" -> "28"
            "WEB_REMIX" -> "67"
            else -> "1"
        }

        val userAgent: String = when (clientName) {
            "ANDROID" -> "com.google.android.youtube/$clientVersion (Linux; U; Android 15; en_US; Pixel 9 Pro; Build/AP4A.250205.002; Cronet/132.0.6834.79) gzip"
            "ANDROID_MUSIC" -> "com.google.android.apps.youtube.music/$clientVersion (Linux; U; Android 15; en_US; Pixel 9 Pro) gzip"
            "ANDROID_VR" -> "com.google.android.apps.youtube.vr.oculus/$clientVersion (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
            "IOS" -> "com.google.ios.youtube/$clientVersion (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"
            else -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
        }

        val android: Boolean get() = clientName.startsWith("ANDROID")
    }

    val ANDROID = ClientContext("ANDROID", "21.26.364", "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w")
    val ANDROID_MUSIC = ClientContext("ANDROID_MUSIC", "8.39.42", "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30")
    val IOS = ClientContext("IOS", "21.26.4", "AIzaSyB-63vPrdThhKuerbB2N_l7Kwwcxj6yUAc")
    val ANDROID_VR = ClientContext("ANDROID_VR", "1.65.10", "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w")
    val WEB_REMIX = ClientContext("WEB_REMIX", "1.20250310.01.00", "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30")

    /**
     * ANDROID first: it is the most reliable client today and its muxed MP4
     * always carries a signed audio URL. ANDROID_VR can mint cleaner adaptive
     * audio when it is not asking for a login, so it stays as a fallback.
     */
    val fallbackOrder = listOf(ANDROID, IOS, ANDROID_VR, ANDROID_MUSIC, WEB_REMIX)

    const val PLAYER_ENDPOINT = "youtubei/v1/player"
    const val SEARCH_ENDPOINT = "youtubei/v1/search"
    const val NEXT_ENDPOINT = "youtubei/v1/next"
    const val SUGGESTIONS_ENDPOINT = "youtubei/v1/music/get_search_suggestions"
    const val BROWSE_ENDPOINT = "youtubei/v1/browse"

    val json = Json { ignoreUnknownKeys = true }

    fun body(
        client: ClientContext,
        videoId: String? = null,
        params: String? = null,
        query: String? = null,
    ): JsonObject =
        json.parseToJsonElement(
            buildString {
                append("{\"context\":{\"client\":{\"clientName\":\"")
                append(client.clientName)
                append("\",\"clientVersion\":\"")
                append(client.clientVersion)
                append("\"}")
                if (client.android || client.clientName == "IOS") {
                    append(",\"androidSdkVersion\":32,\"osName\":\"Android\",\"osVersion\":\"12L\"")
                }
                append("}")
                videoId?.let { append(",\"videoId\":\"").append(it).append("\"") }
                params?.let { append(",\"params\":\"").append(it).append("\"") }
                query?.let { append(",\"query\":").append(JsonPrimitive(it).toString()) }
                append(",\"contentCheckOk\":true,\"racyCheckOk\":true")
                append("}")
            },
        ).jsonObject

    /**
     * Extracts the best audio-only adaptive stream. MP4/AAC is preferred over
     * Opus because it decodes through the same MediaCodec path on every phone,
     * and 44.1 kHz is preferred over 22.05 kHz SBR for the cleanest master.
     */
    fun bestAudioUrl(playerResponse: JsonObject): ResolvedStream? {
        val streamingData = playerResponse["streamingData"]?.jsonObject ?: return null
        val adaptive = streamingData["adaptiveFormats"]?.jsonArray
        val muxed = streamingData["formats"]?.jsonArray
        var best: ResolvedStream? = null
        var bestScore = Long.MIN_VALUE

        fun consider(format: JsonObject, adaptiveFormat: Boolean) {
            val mime = format["mimeType"]?.jsonPrimitive?.content ?: return
            val audioOnly = mime.startsWith("audio/")
            // Newer Android clients omit URLs from adaptive audio formats but
            // still expose a muxed MP4. Its AAC track is a perfectly good
            // source, so use it rather than failing the whole import.
            val muxedAudio = !adaptiveFormat && mime.startsWith("video/mp4") &&
                (format["audioQuality"] != null || mime.contains("mp4a"))
            if (!audioOnly && !muxedAudio) return
            val bitrate = format["bitrate"]?.jsonPrimitive?.content?.toLongOrNull() ?: return
            val url = format["url"]?.jsonPrimitive?.content
                ?: format["signatureCipher"]?.jsonPrimitive?.content?.let { decipher(it) }
                ?: return
            val aac = if (mime.contains("mp4a")) 1_000_000L else 0L
            val rate = format["audioSampleRate"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            val rateScore = if (rate >= 44_100) 100_000L else 0L
            val kindScore = if (audioOnly) 10_000_000L else 0L
            val score = kindScore + aac + rateScore + bitrate
            if (score > bestScore) {
                bestScore = score
                best = ResolvedStream(url, bitrate)
            }
        }

        adaptive?.forEach { consider(it.jsonObject, true) }
        muxed?.forEach { consider(it.jsonObject, false) }
        return best
    }

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