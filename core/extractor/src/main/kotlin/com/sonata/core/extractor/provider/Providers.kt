package com.sonata.core.extractor.provider

import com.sonata.core.domain.model.LinkResolver
import com.sonata.core.domain.model.ResolvedLink
import com.sonata.core.domain.model.SearchResult
import com.sonata.core.domain.model.StreamingProvider
import com.sonata.core.extractor.bitchord.BitchordExtractor
import com.sonata.core.extractor.bitchord.ResolveOutcome
import com.sonata.core.extractor.tiktok.TikTokApi
import com.sonata.core.extractor.tiktok.TikTokUrls
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The local library is the always-working default provider.
 */
class LocalLibraryProvider @Inject constructor() : StreamingProvider {
    override val id = "local"
    override val name = "Local library"
    override val enabled = true
    override suspend fun search(query: String): List<SearchResult> = emptyList()
    override suspend fun resolveStream(trackId: String): ResolvedLink =
        ResolvedLink(title = trackId, audioUrl = trackId)
}

/**
 * YouTube + YouTube Music provider in the ArchiveTune style, fully backed by
 * the BitChord InnerTube extractor: search with suggestions, radio/autoplay,
 * multi-client fallback, expired-URL auto-refresh, region/age handling.
 */
@Singleton
class YouTubeProvider @Inject constructor(
    private val extractor: BitchordExtractor,
) : StreamingProvider {

    override val id = "youtube"
    override val name = "YouTube / YouTube Music"
    override val enabled = true

    private val urlCache = HashMap<String, Pair<String, Long>>()

    override suspend fun search(query: String): List<SearchResult> =
        extractor.search(query, music = true)

    suspend fun searchSuggestions(query: String): List<String> = extractor.searchSuggestions(query)

    override suspend fun resolveStream(trackId: String): ResolvedLink {
        val cached = urlCache[trackId]
        val outcome = if (cached != null) {
            val refreshed = extractor.refreshIfNeeded(trackId, cached.first)
            if (refreshed != null) {
                urlCache[trackId] = refreshed to System.currentTimeMillis()
                return ResolvedLink(title = trackId, audioUrl = refreshed)
            }
            null
        } else {
            null
        }
        if (outcome == null) {
            when (val resolved = extractor.resolveStream(trackId)) {
                is ResolveOutcome.Ok -> {
                    urlCache[trackId] = resolved.url to System.currentTimeMillis()
                    return ResolvedLink(title = trackId, audioUrl = resolved.url)
                }
                is ResolveOutcome.AgeRestricted ->
                    throw IllegalStateException("Age-restricted: sign in through Settings > Sources")
                is ResolveOutcome.RegionBlocked ->
                    throw IllegalStateException("Region-blocked in your current location")
                is ResolveOutcome.Unavailable ->
                    throw IllegalStateException(resolved.reason)
            }
        }
        error("unreachable")
    }

    /** Up-next autoplay for the queue (radio built from the current track). */
    suspend fun autoplay(trackId: String): List<SearchResult> = extractor.radio(trackId)
}

/** Video id extraction from any YouTube / YTM URL shape. */
object YouTubeUrls {
    fun videoId(url: String): String? = when {
        url.contains("youtu.be/") -> url.substringAfter("youtu.be/").substringBefore('?')
            .takeIf { it.isNotBlank() }
        url.contains("watch?v=") -> url.substringAfter("watch?v=").substringBefore('&')
        url.contains("/shorts/") -> url.substringAfter("/shorts/").substringBefore('?')
        url.contains("music.youtube.com") && url.contains("v=") ->
            url.substringAfter("v=").substringBefore('&')
        else -> null
    }

    fun isYouTube(url: String): Boolean =
        url.contains("youtube.com") || url.contains("youtu.be")
}

/**
 * The default link resolver: recognizes YouTube/YouTube Music links and hands
 * them to the BitChord extractor with measured progress callbacks.
 */
@Singleton
class YouTubeLinkResolver @Inject constructor(
    private val provider: YouTubeProvider,
) : LinkResolver {

    override fun canHandle(url: String): Boolean = YouTubeUrls.isYouTube(url)

    override suspend fun resolve(url: String, onProgress: (Float, String) -> Unit): ResolvedLink {
        val videoId = YouTubeUrls.videoId(url)
            ?: throw IllegalArgumentException("Not a supported link")
        onProgress(0.1f, "Resolving")
        val stream = provider.resolveStream(videoId)
        onProgress(0.9f, "Stream ready")
        return stream.copy(title = videoId)
    }
}

/**
 * TikTok share links. The actual import is handled by the worker's TikTok
 * pipeline (it needs the music metadata as well as the video), but registering
 * the resolver keeps every link entry point consistent.
 */
@Singleton
class TikTokLinkResolver @Inject constructor(
    private val api: TikTokApi,
) : LinkResolver {

    override fun canHandle(url: String): Boolean = TikTokUrls.isTikTok(url)

    override suspend fun resolve(url: String, onProgress: (Float, String) -> Unit): ResolvedLink {
        val video = api.resolve(url) { fraction, label -> onProgress(fraction, label) }
        val media = video.videoUrl
            ?: throw IllegalStateException("TikTok did not expose this video's audio")
        return ResolvedLink(
            title = video.displayTitle,
            author = video.displayArtist,
            audioUrl = media,
            durationMs = video.durationMs,
            coverUrl = video.coverUrl,
        )
    }
}

/** Registry that keeps resolvers pluggable (TikTok, YouTube, direct links...). */
@Singleton
class LinkResolverRegistry @Inject constructor(
    private val tiktok: TikTokLinkResolver,
    private val youtube: YouTubeLinkResolver,
    private val direct: DirectAudioResolver,
) {
    val resolvers: List<LinkResolver> get() = listOf(tiktok, youtube, direct)

    fun forUrl(url: String): LinkResolver? = resolvers.firstOrNull { it.canHandle(url) }
}

/** Any direct audio file URL. */
class DirectAudioResolver @Inject constructor() : LinkResolver {
    override fun canHandle(url: String): Boolean =
        url.startsWith("http") && listOf(".mp3", ".m4a", ".aac", ".ogg", ".opus", ".flac", ".wav")
            .any { url.substringBefore('?').endsWith(it) }

    override suspend fun resolve(url: String, onProgress: (Float, String) -> Unit): ResolvedLink {
        onProgress(0.5f, "Linking")
        return ResolvedLink(title = url.substringAfterLast('/'), audioUrl = url)
    }
}