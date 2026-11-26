package com.sonata.core.domain.model

import kotlinx.serialization.Serializable

/**
 * Explicit import checkpoints. Every stage is resumable: downloaded sources
 * stay in the cache, so WorkManager can retry the failed stage instead of
 * starting the whole import again.
 */
enum class ImportStage(val label: String) {
    QUEUED("Queued"),
    FETCH_SOURCE("Fetching source"),
    EXTRACT_AUDIO("Extracting audio"),
    IDENTIFY_SONG("Identifying song"),
    FETCH_FULL_TRACK("Fetching full track"),
    ALIGN_EDIT("Aligning edit"),
    ENCODE_AAC("Encoding audio"),
    FETCH_LYRICS("Fetching lyrics"),
    PERSIST_LIBRARY("Saving library"),
    DONE("Ready"),
    FAILED("Failed"),
    ;

    val terminal: Boolean get() = this == DONE || this == FAILED

    companion object {
        /** Old database rows used the pre-1.4 stage names. */
        fun fromStored(raw: String): ImportStage = runCatching { valueOf(raw) }.getOrElse {
            when (raw) {
                "RESOLVING", "DOWNLOADING" -> FETCH_SOURCE
                "EXTRACTING" -> EXTRACT_AUDIO
                "ANALYZING" -> IDENTIFY_SONG
                "RENDERING" -> ENCODE_AAC
                else -> QUEUED
            }
        }
    }
}

enum class ImportFailureKind(val label: String, val retryable: Boolean) {
    NETWORK("Network unavailable", true),
    RATE_LIMITED("Source is rate limiting", true),
    SOURCE_UNAVAILABLE("Source unavailable", false),
    NO_AUDIO("No audio in this source", false),
    EXTRACTION_FAILED("Audio extraction failed", false),
    TRUNCATED_AUDIO("Audio was incomplete", true),
    NO_RELIABLE_MATCH("No verified full song", true),
    LOW_CONFIDENCE_MATCH("Full song match was not confident", true),
    STORAGE("Not enough storage", false),
    CANCELLED("Import cancelled", false),
    UNKNOWN("Unexpected import error", true),
}

@Serializable
data class ImportJob(
    val id: String,
    val stage: ImportStage = ImportStage.QUEUED,
    val progress: Float = 0f,
    val title: String = "",
    val sourceUri: String = "",
    val error: String? = null,
    val soundId: Long? = null,
    val failure: ImportFailureKind? = null,
    val diagnostics: String? = null,
    val attempt: Int = 0,
    val fullSongVerified: Boolean? = null,
    val matchConfidence: Float? = null,
) {
    val canRetry: Boolean get() = failure?.retryable == true || stage == ImportStage.QUEUED
}

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
