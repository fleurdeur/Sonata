package com.sonata.core.extractor.bitchord

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class BitchordExtractorParseTest {

    @Test
    fun `parses compact video results from the android search client`() {
        val response = buildJsonObject {
            putJsonArray("results") {
                addJsonObject {
                    putJsonObject("compactVideoRenderer") {
                        put("videoId", "dX3k_QDnzHE")
                        putJsonObject("title") {
                            putJsonArray("runs") { addJsonObject { put("text", "M83 Midnight City") } }
                        }
                        putJsonObject("longBylineText") {
                            putJsonArray("runs") { addJsonObject { put("text", "M83") } }
                        }
                        putJsonObject("lengthText") {
                            putJsonArray("runs") { addJsonObject { put("text", "4:04") } }
                        }
                        putJsonObject("thumbnail") {
                            putJsonArray("thumbnails") { addJsonObject { put("url", "https://i.ytimg.com/vi/dX3k_QDnzHE/hqdefault.jpg") } }
                        }
                    }
                }
                addJsonObject {
                    putJsonObject("videoRenderer") {
                        put("videoId", "second01")
                        putJsonObject("title") {
                            putJsonArray("runs") { addJsonObject { put("text", "Second") } }
                        }
                        putJsonObject("ownerText") {
                            putJsonArray("runs") { addJsonObject { put("text", "Artist - Topic") } }
                        }
                        putJsonObject("lengthText") {
                            putJsonArray("runs") { addJsonObject { put("text", "3:20") } }
                        }
                    }
                }
            }
        }

        val results = BitchordExtractor().parseSearch(response, "ANDROID")

        assertEquals(2, results.size)
        val resultsById = results.associateBy { it.id }
        val compact = resultsById.getValue("dX3k_QDnzHE")
        assertEquals("M83 Midnight City", compact.title)
        assertEquals("M83", compact.author)
        assertEquals(244_000L, compact.durationMs)
        assertEquals(
            "https://i.ytimg.com/vi/dX3k_QDnzHE/hqdefault.jpg",
            compact.thumbnailUrl,
        )
        assertEquals("Second", resultsById.getValue("second01").title)
    }
}