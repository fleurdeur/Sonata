package com.sonata.core.domain.model

enum class CueKind(val label: String) {
    HOT("Hot cue"),
    MEMORY("Memory"),
    LOOP("Loop"),
}

/** One saved moment in a recording. [endMs] is only used by loop cues. */
data class Cue(
    val id: Long = 0,
    val soundId: Long,
    val positionMs: Long,
    val endMs: Long? = null,
    val label: String,
    val kind: CueKind = CueKind.MEMORY,
    val createdAt: Long = 0,
)