package com.sonata.core.domain.model

import kotlinx.serialization.Serializable

enum class ImportStage { QUEUED, RESOLVING, DOWNLOADING, EXTRACTING, ANALYZING, DONE, FAILED }

@Serializable
data class ImportJob(
    val id: String,
    val stage: ImportStage = ImportStage.QUEUED,
    val progress: Float = 0f,
    val title: String = "",
    val sourceUri: String = "",
    val error: String? = null,
    val soundId: Long? = null,
)

/** Pluggable link resolver: turns a shared URL into an audio stream. */
interface LinkResolver {
    fun canHandle(url: String): Boolean
    suspend fun resolve(url: String, onProgress: (Float, String) -> Unit): ResolvedLink
}

data class ResolvedLink(
    val title: String,
    val author: String? = null,
    val audioUrl: String,
    val durationMs: Long = 0,
    val coverUrl: String? = null,
)

/** Pluggable streaming provider SPI (local library is the default). */
interface StreamingProvider {
    val id: String
    val name: String
    val enabled: Boolean
    suspend fun search(query: String): List<SearchResult>
    suspend fun resolveStream(trackId: String): ResolvedLink
}

data class SearchResult(
    val id: String,
    val title: String,
    val author: String? = null,
    val durationMs: Long = 0,
    val thumbnailUrl: String? = null,
    val providerId: String,
)
