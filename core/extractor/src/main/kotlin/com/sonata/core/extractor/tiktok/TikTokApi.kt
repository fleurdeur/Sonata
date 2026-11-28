package com.sonata.core.extractor.tiktok

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Everything Sonata needs from one TikTok post. */
data class TikTokVideo(
    val awemeId: String,
    val shareUrl: String,
    val description: String,
    val authorHandle: String?,
    val authorName: String?,
    val videoUrl: String?,
    val durationMs: Long,
    val coverUrl: String?,
    val musicTitle: String?,
    val musicAuthor: String?,
    val musicDurationMs: Long,
    val musicAudioUrl: String?,
    val isOriginalSound: Boolean,
    val matchedSongTitle: String? = null,
    val matchedSongArtist: String? = null,
    val album: String? = null,
    val musicId: String? = null,
    val hashtags: List<String> = emptyList(),
    val searchHints: List<TrackHint> = emptyList(),
    val effectHints: Set<String> = emptySet(),
) {
    /** The title a human would recognise in a library. */
    val displayTitle: String
        get() {
            val matched = matchedSongTitle?.trim().orEmpty()
            if (matched.isNotBlank()) return matched
            if (!isOriginalSound) {
                CaptionParser.cleanSoundTitle(musicTitle)?.let { return it }
            }
            searchHints.firstOrNull { it.source == "caption" || it.source == "hashtag" }
                ?.title?.takeIf { it.isNotBlank() }?.let { return it }
            val desc = description.trim().lineSequence().firstOrNull().orEmpty()
            return desc.take(80).ifBlank { "TikTok sound" }
        }

    val displayArtist: String?
        get() = matchedSongArtist?.trim()?.takeIf { it.isNotBlank() }
            ?: musicAuthor?.trim()?.takeIf {
                it.isNotBlank() && !it.equals("TikTok", true) && !it.equals("original sound", true)
            }
            ?: searchHints.firstOrNull()?.artist

    /** Best first search identity, or null when the sound has no song name. */
    val fullTrackQuery: String?
        get() = searchHints.firstOrNull()?.let { hint ->
            listOfNotNull(hint.title, hint.artist).joinToString(" ")
        }

    /** Explicit matched song, sound name and caption credit, best first. */
    val fullTrackHints: List<TrackHint>
        get() = searchHints
}

/** TikTok share links: canonical, mobile and short (vt/vm) forms. */
object TikTokUrls {
    private val hostRegex = Regex("""(?i)(?:https?://)?(?:[a-z0-9-]+\.)*(?:tiktok\.com|douyin\.com|iesdouyin\.com)/[^\s]+""")

    fun firstUrlIn(text: String): String? =
        hostRegex.find(text)?.value?.trimEnd('.', ',', ')', ']', '"', '\'')?.let { candidate ->
            if (candidate.startsWith("http")) candidate else "https://$candidate"
        }

    fun isTikTok(url: String): Boolean =
        url.contains("tiktok.com", true) || url.contains("douyin.com", true)

    fun awemeId(url: String): String? {
        val patterns = listOf(
            Regex("/video/(\\d{6,})"),
            Regex("/photo/(\\d{6,})"),
            Regex("/v/(\\d{6,})"),
            Regex("[?&]item_id=(\\d{6,})"),
            Regex("[?&]aweme_id=(\\d{6,})"),
        )
        for (p in patterns) p.find(url)?.groupValues?.getOrNull(1)?.let { return it }
        return null
    }

    fun isShortLink(url: String): Boolean =
        Regex("(?i)https?://(?:vt|vm|www)\\.tiktok\\.com/(?:t/)?[A-Za-z0-9]{4,}/?").matches(url.trim()) &&
            awemeId(url) == null
}

/**
 * TikTok client, no login and no signing: the public mobile endpoint that the
 * app itself uses for one aweme, plus a plain downloader for the media.
 */
class TikTokApi @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val deviceId: String = randomHex(20)
    private val installId: String = randomHex(16)
    private val openUdid: String = randomHex(16)

    suspend fun resolve(url: String, onProgress: suspend (Float, String) -> Unit = { _, _ -> }): TikTokVideo =
        withContext(Dispatchers.IO) {
            onProgress(0.05f, "Reading link")
            val canonical = if (TikTokUrls.awemeId(url) != null) url else followRedirects(url)
            val id = TikTokUrls.awemeId(canonical)
                ?: throw IllegalArgumentException("This TikTok link does not point to a video")
            onProgress(0.2f, "Looking up the sound")
            val video = fetch(id, canonical)
            onProgress(0.45f, video.displayTitle.take(60))
            video
        }

    /** Resolves vt.tiktok.com / vm.tiktok.com redirects to the canonical URL. */
    fun followRedirects(url: String, maxHops: Int = 6): String {
        var current = url
        repeat(maxHops) {
            val connection = open(current, "HEAD")
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location") ?: return current
                    current = URL(URL(current), location).toString()
                    if (TikTokUrls.awemeId(current) != null) return current
                } else {
                    return current
                }
            } finally {
                connection.disconnect()
            }
        }
        return current
    }

    /** One aweme, with retries for TikTok's aggressive rate limiting. */
    suspend fun fetch(awemeId: String, shareUrl: String): TikTokVideo {
        var lastError: Throwable? = null
        var waitMs = 1_200L
        repeat(3) { attempt ->
            try {
                val response = callApi(awemeId)
                if (response != null) return parse(response, shareUrl)
                lastError = IllegalStateException("TikTok returned no video for this link")
            } catch (t: Throwable) {
                lastError = t
            }
            if (attempt < 2) delay(waitMs)
            waitMs *= 2
        }
        throw IllegalStateException(
            lastError?.message?.let { "TikTok unavailable: $it" }
                ?: "TikTok unavailable right now. Try again in a moment.",
            lastError,
        )
    }

    /** POSTs to the mobile detail endpoint; null means "no item". */
    private fun callApi(awemeId: String): JsonObject? {
        val params = deviceParams()
        val url = "https://api16-normal-c-useast1a.tiktokv.com/aweme/v1/multi/aweme/detail/?" +
            params.entries.joinToString("&") { (k, v) ->
                "$k=${URLEncoder.encode(v, "UTF-8")}"
            }
        val connection = open(url, "POST")
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val body = "aweme_ids=" + URLEncoder.encode("[$awemeId]", "UTF-8")
            connection.outputStream.use { it.write(body.toByteArray()) }
            val code = connection.responseCode
            if (code == 429) throw IllegalStateException("TikTok is rate limiting. Try again in a minute.")
            if (code !in 200..299) throw IllegalStateException("TikTok HTTP $code")
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            if (text.isBlank()) throw IllegalStateException("Empty response from TikTok")
            val root = json.parseToJsonElement(text).jsonObject
            val item = root["aweme_details"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
            return item
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(item: JsonObject, shareUrl: String): TikTokVideo {
        val id = item.string("aweme_id") ?: error("missing aweme id")
        val video = item["video"] as? JsonObject
        val music = item["music"] as? JsonObject
        val author = item["author"] as? JsonObject
        val videoDuration = video?.long("duration") ?: 0L
        val musicDuration = music?.long("duration")?.let { it * 1000 } ?: 0L
        val description = item.string("desc").orEmpty()
        val hashtags = hashtags(item, description)
        val original = music?.bool("is_original_sound") == true ||
            music?.bool("is_original") == true ||
            music?.string("title").orEmpty().startsWith("original sound", true)
        val matched = findSongInfo(music, item)
        val hints = CaptionParser.hints(
            description = description,
            hashtags = hashtags,
            soundTitle = music?.string("title"),
            soundAuthor = music?.string("author"),
            matchedTitle = matched?.first,
            matchedArtist = matched?.second,
            isOriginalSound = original,
        )
        if (android.util.Log.isLoggable("SonataTikTok", android.util.Log.DEBUG) && music != null) {
            android.util.Log.d("SonataTikTok", "music=" + music.toString().take(4_000))
        }
        return TikTokVideo(
            awemeId = id,
            shareUrl = shareUrl,
            description = description,
            authorHandle = author?.string("unique_id"),
            authorName = author?.string("nickname"),
            videoUrl = video?.let(::bestUrl),
            durationMs = videoDuration,
            coverUrl = video?.get("cover")?.let(::firstUrl)
                ?: video?.get("origin_cover")?.let(::firstUrl),
            musicTitle = music?.string("title"),
            musicAuthor = music?.string("author"),
            musicDurationMs = musicDuration,
            musicAudioUrl = music?.let(::bestUrl) ?: music?.string("strong_beat_url"),
            isOriginalSound = original,
            matchedSongTitle = matched?.first,
            matchedSongArtist = matched?.second,
            album = findString(music, "album", "album_name", "album_title"),
            musicId = music?.string("music_id") ?: music?.string("id") ?: music?.string("mid"),
            hashtags = hashtags,
            searchHints = hints,
            effectHints = CaptionParser.effects(description, hashtags),
        )
    }

    /** The matched/external song block has changed names over time; probe common shapes. */
    private fun findSongInfo(music: JsonObject?, root: JsonObject): Pair<String, String?>? {
        val blocks = listOfNotNull(
            music?.get("matched_song") as? JsonObject,
            music?.get("external_song_info") as? JsonObject,
            music?.get("song_info") as? JsonObject,
            root["matched_song"] as? JsonObject,
            root["external_song_info"] as? JsonObject,
        )
        for (block in blocks) {
            val title = findString(block, "song_name", "music_name", "title", "name")
            if (!title.isNullOrBlank()) {
                val artist = findString(block, "artist_name", "author_name", "singer_name", "artist", "author")
                return title to artist
            }
        }
        val title = music?.string("matched_song_title") ?: music?.string("song_name")
        if (!title.isNullOrBlank()) {
            return title to (music?.string("matched_song_artist") ?: music?.string("artist_name"))
        }
        return null
    }

    private fun hashtags(item: JsonObject, description: String): List<String> {
        val fromApi = (item["text_extra"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { extra ->
                (extra as? JsonObject)?.string("hashtag_name")?.takeIf { it.isNotBlank() }
            }.orEmpty()
        val fromText = Regex("""#([\p{L}\p{N}_]{2,40})""").findAll(description)
            .map { it.groupValues[1] }.toList()
        return (fromApi + fromText).distinct()
    }

    private fun findString(obj: JsonObject?, vararg keys: String): String? {
        if (obj == null) return null
        for (key in keys) obj.string(key)?.takeIf { it.isNotBlank() }?.let { return it }
        return null
    }

    private fun bestUrl(obj: JsonObject): String? {
        (obj["play_url"] as? JsonObject)?.let { firstUrl(it) }?.let { return it }
        (obj["play_addr"] as? JsonObject)?.let { firstUrl(it) }?.let { return it }
        (obj["download_addr"] as? JsonObject)?.let { firstUrl(it) }?.let { return it }
        return (obj["url_list"] as? kotlinx.serialization.json.JsonArray)
            ?.firstNotNullOfOrNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
    }

    private fun firstUrl(obj: kotlinx.serialization.json.JsonElement): String? {
        val o = obj as? JsonObject ?: return null
        val list = o["url_list"] as? kotlinx.serialization.json.JsonArray ?: return null
        return list.firstNotNullOfOrNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
    }

    private fun open(url: String, method: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.setRequestProperty(
            "User-Agent",
            "com.ss.android.ugc.trill/390205 (Linux; U; Android 13; en_US; Pixel 7; Build/TQ3A.230805.001)",
        )
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        return connection
    }

    private fun deviceParams(): Map<String, String> {
        val now = System.currentTimeMillis()
        return linkedMapOf(
            "device_platform" to "android",
            "os" to "android",
            "ssmix" to "a",
            "_rticket" to now.toString(),
            "cdid" to installId,
            "channel" to "googleplay",
            "aid" to "1988",
            "app_name" to "trill",
            "version_code" to "390205",
            "version_name" to "39.2.5",
            "manifest_version_code" to "390205",
            "update_version_code" to "390205",
            "ab_version" to "39.2.5",
            "resolution" to "1080*2400",
            "dpi" to "420",
            "device_type" to "Pixel 7",
            "device_brand" to "Google",
            "language" to "en",
            "os_api" to "29",
            "os_version" to "13",
            "ac" to "wifi",
            "is_pad" to "0",
            "current_region" to "US",
            "app_type" to "normal",
            "sys_region" to "US",
            "app_language" to "en",
            "timezone_name" to "America/New_York",
            "timezone_offset" to "-14400",
            "host_abi" to "armeabi-v7a",
            "locale" to "en",
            "ac2" to "wifi5g",
            "uoo" to "1",
            "op_region" to "US",
            "region" to "US",
            "ts" to (now / 1000).toString(),
            "iid" to "7318518857994389254",
            "device_id" to deviceId,
            "openudid" to openUdid,
        )
    }

    private fun randomHex(length: Int): String {
        val chars = "0123456789abcdef"
        return (0 until length).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }

    private fun JsonObject.string(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()

    private fun JsonObject.long(key: String): Long? =
        runCatching { this[key]?.jsonPrimitive?.content?.toLongOrNull() }.getOrNull()

    private fun JsonObject.bool(key: String): Boolean? =
        runCatching { this[key]?.jsonPrimitive?.content?.toBooleanStrictOrNull() }.getOrNull()

    companion object {
        /** Fallback when a share contains an escaped or shortened URL. */
        fun decodeSharedText(text: String): String? {
            val unescaped = runCatching { URLDecoder.decode(text, "UTF-8") }.getOrDefault(text)
            return TikTokUrls.firstUrlIn(unescaped)
        }
    }
}
/**
 * Streams remote media to disk atomically. Bytes go to a .part file, the
 * declared content length is verified, and only a complete response is
 * renamed onto [destination]. A killed import can therefore never leave a
 * half-written file that later looks like a valid cache hit.
 */
class MediaDownloader @Inject constructor() {

    suspend fun download(
        url: String,
        destination: File,
        userAgent: String = "Mozilla/5.0 (Linux; Android 13; Pixel 7)",
        referer: String = "https://www.tiktok.com/",
        onProgress: suspend (Float) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        destination.parentFile?.mkdirs()
        val part = File(destination.parentFile, destination.name + ".part")
        part.delete()
        var current = url
        var redirects = 0
        try {
            while (redirects < 5) {
                val connection = URL(current).openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 20_000
                connection.readTimeout = 60_000
                connection.setRequestProperty("User-Agent", userAgent)
                connection.setRequestProperty("Referer", referer)
                connection.setRequestProperty("Accept", "*/*")
                try {
                    val code = connection.responseCode
                    if (code in 300..399) {
                        current = connection.getHeaderField("Location")
                            ?: error("Download redirect without a location")
                        redirects++
                        continue
                    }
                    if (code !in 200..299) error("Download failed: HTTP $code")
                    val total = connection.contentLengthLong
                    var written = 0L
                    connection.inputStream.use { input ->
                        part.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                written += read
                                if (total > 0) {
                                    onProgress((written.toFloat() / total).coerceIn(0f, 1f))
                                }
                            }
                            output.flush()
                        }
                    }
                    if (written <= 0L) error("Downloaded an empty file")
                    if (total > 0 && written != total) {
                        error("Incomplete download: $written of $total bytes")
                    }
                    if (!part.renameTo(destination)) {
                        part.copyTo(destination, overwrite = true)
                    }
                    return@withContext destination
                } finally {
                    connection.disconnect()
                }
            }
            error("Too many redirects")
        } finally {
            part.delete()
        }
    }
}