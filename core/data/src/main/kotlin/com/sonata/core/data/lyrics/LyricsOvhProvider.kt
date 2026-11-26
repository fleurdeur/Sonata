package com.sonata.core.data.lyrics

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Plain-text fallback with wide coverage of older pop catalogues. */
@Singleton
class LyricsOvhProvider @Inject constructor() : LyricsProvider {

    override val id = "lyrics.ovh"

    private val http = LyricsHttp()

    override suspend fun fetch(query: LyricsQuery): ProviderLyrics? {
        val artist = query.cleanArtist ?: return null
        val title = query.cleanTitle.takeIf { it.isNotBlank() } ?: return null
        val url = "https://api.lyrics.ovh/v1/" + LyricsHttp.encode(artist) + "/" + LyricsHttp.encode(title)
        val obj = http.getObject(url) ?: return null
        val text = obj["lyrics"]?.jsonPrimitive?.contentOrNull
            ?.replace("\r\n", "\n")
            ?.trim()
            ?.takeIf { it.length > 20 && !it.contains("not found", ignoreCase = true) }
            ?: return null
        return ProviderLyrics(
            provider = id,
            plainText = text,
            trackName = title,
            artistName = artist,
            confidence = 0.62f,
        )
    }
}
