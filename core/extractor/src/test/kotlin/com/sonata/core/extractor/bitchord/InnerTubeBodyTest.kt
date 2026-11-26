package com.sonata.core.extractor.bitchord

import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class InnerTubeBodyTest {

    @Test
    fun search_body_contains_the_query_and_params() {
        val body = InnerTube.body(
            InnerTube.ANDROID,
            params = "EgIQAQ%3D%3D",
            query = "M83 Midnight City official audio",
        )
        assertEquals("M83 Midnight City official audio", body["query"]?.jsonPrimitive?.content)
        assertEquals("EgIQAQ%3D%3D", body["params"]?.jsonPrimitive?.content)
    }

    @Test
    fun query_is_json_escaped() {
        val body = InnerTube.body(InnerTube.ANDROID, query = "Say \"Hello\" \\ 世界")
        assertEquals("Say \"Hello\" \\ 世界", body["query"]?.jsonPrimitive?.content)
    }
}
