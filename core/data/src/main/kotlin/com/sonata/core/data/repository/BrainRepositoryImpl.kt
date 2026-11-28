package com.sonata.core.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sonata.core.database.dao.SoundDao
import com.sonata.core.database.entity.SoundEntity
import com.sonata.core.domain.repository.BrainContext
import com.sonata.core.domain.repository.BrainRepository
import com.sonata.core.domain.repository.Suggestion
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** What Sonata Brain has learned, editable and resettable on the Taste Profile screen. */
data class BrainLearningState(
    val suggestionsOff: Boolean = false,
    val topSoundId: Long? = null,
    val topBpm: Int? = null,
    val dismissed: Set<String> = emptySet(),
    val neverSuggest: Set<String> = emptySet(),
    val acceptedCount: Int = 0,
    val acceptedPreset: String? = null,
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
        val preset = stringPreferencesKey("accepted_preset")
    }

    fun observe(): Flow<BrainLearningState> = context.brainStore.data.map { p ->
        BrainLearningState(
            suggestionsOff = (p[Keys.off] as? Int ?: 0) > 0,
            topSoundId = (p[Keys.topSound] as? String)?.toLongOrNull(),
            topBpm = (p[Keys.topBpm] as? String)?.toIntOrNull(),
            dismissed = (p[Keys.dismissed] as? String ?: "").split('|').filter { it.isNotBlank() }.toSet(),
            neverSuggest = (p[Keys.never] as? String ?: "").split('|').filter { it.isNotBlank() }.toSet(),
            acceptedCount = p[Keys.accepted] as? Int ?: 0,
            acceptedPreset = p[Keys.preset] as? String,
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
        suggestion.presetName?.let { p[Keys.preset] = it }
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
    private val soundDao: SoundDao,
) : BrainRepository {

    override fun observeSuggestions(context: BrainContext): Flow<List<Suggestion>> =
        combine(learning.observe(), soundDao.observeAll()) { state, sounds ->
            if (state.suggestionsOff || sounds.isEmpty()) {
                emptyList()
            } else {
                BrainPolicy.filter(state, rawSuggestions(state, context, sounds))
            }
        }

    /**
     * Ideas come from what is actually in the library: the artist and genre
     * the user replays most, the tempo they gravitate to, and the current
     * listening context. No random or invented recommendations.
     */
    private fun rawSuggestions(
        state: BrainLearningState,
        context: BrainContext,
        sounds: List<SoundEntity>,
    ): List<Suggestion> = buildList {
        val playable = sounds.filter { it.importStatus == "READY" && it.filePath.isNotBlank() }
        if (playable.isEmpty()) return@buildList

        val artist = playable
            .mapNotNull { it.artist?.trim()?.takeIf(String::isNotBlank)?.let { name -> name to it } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size >= 2 }
            .maxByOrNull { (_, tracks) -> tracks.sumOf { it.playCount.coerceAtLeast(0) } }
        artist?.let { (name, tracks) ->
            val pick = tracks.minByOrNull { it.playCount } ?: tracks.first()
            add(
                Suggestion(
                    id = "artist_" + name.lowercase().replace(' ', '_') + "_" + pick.id,
                    headline = "More from " + name,
                    reason = "You have " + tracks.size + " " + name + " tracks in your library",
                    soundId = pick.id,
                ),
            )
        }

        val genre = playable
            .mapNotNull { it.genre?.trim()?.takeIf(String::isNotBlank)?.let { name -> name to it } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size >= 2 }
            .maxByOrNull { (_, tracks) -> tracks.sumOf { it.playCount.coerceAtLeast(0) } }
        genre?.let { (name, tracks) ->
            val pick = tracks.minByOrNull { it.playCount } ?: tracks.first()
            add(
                Suggestion(
                    id = "genre_" + name.lowercase().replace(' ', '_') + "_" + pick.id,
                    headline = name + " for right now",
                    reason = "Your most-played genre is " + name,
                    soundId = pick.id,
                ),
            )
        }

        val bpm = playable.mapNotNull { it.bpm?.takeIf { value -> value in 50..220 } }
        if (bpm.size >= 2) {
            val target = bpm.sorted()[bpm.size / 2]
            val pick = playable
                .mapNotNull { sound -> sound.bpm?.let { value -> sound to abs(value - target) } }
                .minByOrNull { it.second }
                ?.first
            pick?.let {
                add(
                    Suggestion(
                        id = "tempo_" + it.id + "_" + target,
                        headline = "Keep the " + target + " BPM energy",
                        reason = "This is the tempo you replay most",
                        soundId = it.id,
                    ),
                )
            }
        }

        if (state.topSoundId != null) {
            playable.firstOrNull { it.id == state.topSoundId }?.let {
                state.acceptedPreset?.let { preset ->
                    add(
                        Suggestion(
                            id = "preset_" + preset.lowercase().replace(' ', '_') + "_" + it.id,
                            headline = "Try " + preset + " again",
                            reason = "You accepted this preset before",
                            soundId = it.id,
                            presetName = preset,
                        ),
                    )
                }
            }
        }

        if (context.isBluetooth) {
            add(
                Suggestion(
                    id = "device_car",
                    headline = "Switch to the Car profile",
                    reason = "Audio is playing over Bluetooth",
                    presetName = "Car",
                ),
            )
        }
        if (context.hourOfDay in 22..23 || context.hourOfDay < 6) {
            add(
                Suggestion(
                    id = "time_late_night",
                    headline = "Late Night preset fits right now",
                    reason = "It is late and your volume is " + context.volumePercent + "%",
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
