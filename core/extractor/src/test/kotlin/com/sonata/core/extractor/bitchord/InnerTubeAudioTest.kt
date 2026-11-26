package com.sonata.core.extractor.bitchord

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InnerTubeAudioTest {

    @Test
    fun `falls back to muxed mp4 audio when adaptive urls are omitted`() {
        val response = buildJsonObject {
            putJsonObject("streamingData") {
                putJsonArray("adaptiveFormats") {
                    addJsonObject {
                        put("mimeType", "audio/mp4; codecs=\"mp4a.40.2\"")
                        put("bitrate", "129000")
                        put("audioSampleRate", "44100")
                    }
                }
                putJsonArray("formats") {
                    addJsonObject {
                        put("mimeType", "video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"")
                        put("bitrate", "245000")
                        put("audioSampleRate", "44100")
                        put("audioQuality", "AUDIO_QUALITY_LOW")
                        put("url", "https://example.test/muxed")
                    }
                }
            }
        }

        val stream = requireNotNull(InnerTube.bestAudioUrl(response))

        assertEquals("https://example.test/muxed", stream.url)
        assertEquals(245000L, stream.bitrate)
    }

    @Test
    fun `prefers a real adaptive audio stream over muxed video`() {
        val response = buildJsonObject {
            putJsonObject("streamingData") {
                putJsonArray("adaptiveFormats") {
                    addJsonObject {
                        put("mimeType", "audio/mp4; codecs=\"mp4a.40.2\"")
                        put("bitrate", "129000")
                        put("audioSampleRate", "44100")
                        put("url", "https://example.test/audio")
                    }
                }
                putJsonArray("formats") {
                    addJsonObject {
                        put("mimeType", "video/mp4")
                        put("bitrate", "999000")
                        put("audioQuality", "AUDIO_QUALITY_LOW")
                        put("url", "https://example.test/muxed")
                    }
                }
            }
        }

        assertEquals(
            "https://example.test/audio",
            requireNotNull(InnerTube.bestAudioUrl(response)).url,
        )
    }

    @Test
    fun `returns null when youtube supplies no playable audio url`() {
        val response = buildJsonObject {
            putJsonObject("streamingData") {
                putJsonArray("adaptiveFormats") {
                    addJsonObject {
                        put("mimeType", "audio/webm; codecs=\"opus\"")
                        put("bitrate", "160000")
                    }
                }
            }
        }
        assertNull(InnerTube.bestAudioUrl(response))
    }
}