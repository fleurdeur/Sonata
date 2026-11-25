package com.sonata.core.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sonata.core.domain.repository.BrainContext
import com.sonata.core.domain.repository.BrainRepository
import com.sonata.core.domain.repository.Suggestion
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** What Sonata Brain has learned, editable and resettable on the Taste Profile screen. */
data class BrainLearningState(
    val suggestionsOff: Boolean = false,
    val topSoundId: Long? = null,
    val topBpm: Int? = null,
    val dismissed: Set<String> = emptySet(),
    val neverSuggest: Set<String> = emptySet(),
    val acceptedCount: Int = 0,
)

private val Context.brainStore: DataStore<Preferences> by preferencesDataStore(name = "sonata_brain")

@Singleton
class BrainLearningStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val off = intPreferencesKey("suggestions_off")
        val topSound = stringPreferencesKey("top_sound")
        val topBpm = stringPreferencesKey("top_bpm")
        val dismissed = stringPreferencesKey("dismissed")
        val never = stringPreferencesKey("never")
        val accepted = intPreferencesKey("accepted_count")
    }

    fun observe(): Flow<BrainLearningState> = context.brainStore.data.map { p ->
        BrainLearningState(
            suggestionsOff = (p[Keys.off] as? Int ?: 0) > 0,
            topSoundId = (p[Keys.topSound] as? String)?.toLongOrNull(),
            topBpm = (p[Keys.topBpm] as? String)?.toIntOrNull(),
            dismissed = (p[Keys.dismissed] as? String ?: "").split('|').filter { it.isNotBlank() }.toSet(),
            neverSuggest = (p[Keys.never] as? String ?: "").split('|').filter { it.isNotBlank() }.toSet(),
            acceptedCount = p[Keys.accepted] as? Int ?: 0,
        )
    }

    suspend fun dismiss(id: String) { context.brainStore.edit { p ->
        p[Keys.dismissed] = ((p[Keys.dismissed] as? String ?: "") + "|" + id).trim('|')
    }
    }

    suspend fun neverSuggest(id: String) { context.brainStore.edit { p ->
        p[Keys.never] = ((p[Keys.never] as? String ?: "") + "|" + id).trim('|')
    }
    }

    suspend fun accept(suggestion: Suggestion) { context.brainStore.edit { p ->
        p[Keys.accepted] = (p[Keys.accepted] as? Int ?: 0) + 1
        suggestion.soundId?.let { p[Keys.topSound] = it.toString() }
        suggestion.presetName?.let { p[Keys.topBpm] = (p[Keys.topBpm] as? String ?: "0") }
    }
    }

    suspend fun reset() { context.brainStore.edit { it.clear() } }
}

/** Filtering layer between Brain's raw ideas and what the user actually sees. */
object BrainPolicy {
    fun filter(state: BrainLearningState, raw: List<Suggestion>): List<Suggestion> =
        raw.filter { s -> s.id !in state.dismissed && s.id !in state.neverSuggest }
}

@Singleton
class BrainRepositoryImpl @Inject constructor(
    private val learning: BrainLearningStore,
) : BrainRepository {

    override fun observeSuggestions(context: BrainContext): Flow<List<Suggestion>> =
        learning.observe().map { state ->
            if (state.suggestionsOff) {
                emptyList()
            } else {
                BrainPolicy.filter(state, rawSuggestions(state, context))
            }
        }

    private fun rawSuggestions(state: BrainLearningState, context: BrainContext): List<Suggestion> =
        buildList {
            state.topSoundId?.let { soundId ->
                add(
                    Suggestion(
                        id = "preset_hardstyle_$soundId",
                        headline = "Try Hardstyle on this clip",
                        reason = "Suggested because this clip is ${state.topBpm ?: 140} BPM with heavy bass",
                        soundId = soundId,
                        presetName = "Hardstyle",
                    ),
                )
            }
            if (context.isBluetooth) {
                add(
                    Suggestion(
                        id = "device_car",
                        headline = "Switch to the Car profile",
                        reason = "Suggested because audio is playing over Bluetooth",
                        presetName = "Car",
                    ),
                )
            }
            if (context.hourOfDay in 22..23 || context.hourOfDay < 6) {
                add(
                    Suggestion(
                        id = "time_late_night",
                        headline = "Late Night preset fits right now",
                        reason = "Suggested because it is ${context.hourOfDay}:00 and you keep the volume low",
                        presetName = "Late Night",
                    ),
                )
            }
        }

    override suspend fun dismiss(id: String) = learning.dismiss(id)

    override suspend fun neverSuggest(id: String) = learning.neverSuggest(id)

    override suspend fun accept(suggestion: Suggestion) = learning.accept(suggestion)

    override suspend fun resetLearning() = learning.reset()
}
