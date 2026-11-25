package com.sonata.core.domain.repository

import com.sonata.core.domain.model.Sound
import kotlinx.coroutines.flow.Flow

data class Suggestion(
    val id: String,
    val headline: String,
    val reason: String,
    val soundId: Long? = null,
    val presetName: String? = null,
    val dismissible: Boolean = true,
)

interface BrainRepository {
    fun observeSuggestions(context: BrainContext): Flow<List<Suggestion>>
    suspend fun dismiss(id: String)
    suspend fun neverSuggest(id: String)
    suspend fun accept(suggestion: Suggestion)
    suspend fun resetLearning()
}

/** Ambient context Brain uses for suggestions (all optional). */
data class BrainContext(
    val hourOfDay: Int,
    val isHeadphones: Boolean,
    val isBluetooth: Boolean,
    val volumePercent: Int,
)
