package com.sonata.feature.mix

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonata.core.designsystem.Dimensions
import com.sonata.core.designsystem.LocalSpacing
import com.sonata.core.designsystem.components.EmptyState
import com.sonata.core.designsystem.components.ScreenHeader
import com.sonata.core.designsystem.components.SoundRow
import com.sonata.core.designsystem.components.formatDuration
import com.sonata.core.domain.model.Sound
import com.sonata.core.domain.repository.SoundRepository
import com.sonata.core.media.connection.PlayerConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.min
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class MixViewModel @Inject constructor(
    private val soundRepository: SoundRepository,
    private val player: PlayerConnection,
) : ViewModel() {

    val sounds = soundRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun play(order: List<Sound>, index: Int = 0) = viewModelScope.launch {
        if (order.isEmpty()) return@launch
        player.play(order, index, transitionFade = true)
        soundRepository.incrementPlayCount(order[index].id)
    }
}

/**
 * Harmonic mixing without new machinery: the library is ordered so each track
 * is close in tempo (within a few BPM) and compatible in Camelot key with the
 * one before it. That order is the mix; playing it is one button.
 */
@Composable
fun MixScreen(viewModel: MixViewModel = hiltViewModel()) {
    val sounds by viewModel.sounds.collectAsState()
    val spacing = LocalSpacing.current
    var seedId by remember { mutableStateOf<Long?>(null) }
    var manualIds by remember { mutableStateOf<List<Long>>(emptyList()) }

    val harmonic = remember(sounds, seedId) { harmonicOrder(sounds, seedId) }
    val order = remember(harmonic, manualIds, sounds) {
        if (manualIds.isEmpty()) {
            harmonic
        } else {
            val byId = sounds.associateBy { it.id }
            val arranged = manualIds.mapNotNull(byId::get)
            val known = arranged.map { it.id }.toSet()
            arranged + harmonic.filter { it.id !in known }
        }
    }
    val isManual = manualIds.isNotEmpty()

    fun move(from: Int, to: Int) {
        if (from == to || from !in order.indices || to !in order.indices) return
        val ids = order.map { it.id }.toMutableList()
        val item = ids.removeAt(from)
        ids.add(to, item)
        manualIds = ids
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(horizontal = spacing.mdDp),
        contentPadding = PaddingValues(bottom = Dimensions.listBottomPadding),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item {
            ScreenHeader(
                title = "Mix",
                subtitle = when {
                    sounds.size < 2 -> "Add sounds to build a mix"
                    isManual -> "${sounds.size} sounds · your custom order"
                    else -> "${sounds.size} sounds in harmonic order"
                },
            )
        }
        item {
            Column(Modifier.fillMaxWidth()) {
                Button(
                    onClick = { viewModel.play(order) },
                    enabled = sounds.size >= 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) { Text("Play the mix") }
                Text(
                    if (isManual) {
                        "Use the arrows on any track to reorder the queue. Your order is what plays next."
                    } else {
                        "Each next track is chosen for a small tempo step and a compatible Camelot key. Reorder any track with the arrows."
                    },
                    Modifier.padding(top = spacing.smDp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (isManual) {
                    TextButton(onClick = { manualIds = emptyList() }) {
                        Text("Back to harmonic order")
                    }
                }
            }
        }
        if (sounds.size < 2) {
            item {
                EmptyState(
                    title = "Not enough sounds",
                    message = "A mix needs at least two tracks. Import a few from TikTok or your files.",
                )
            }
        } else {
            items(order, key = { it.id }) { sound ->
                SoundRow(
                    sound = sound,
                    onPlay = { viewModel.play(order, order.indexOf(sound)) },
                    onClick = {
                        // Re-seed the path so the mix starts from this track.
                        seedId = sound.id
                        manualIds = emptyList()
                        viewModel.play(harmonicOrder(sounds, sound.id), 0)
                    },
                    trailing = {
                        val index = order.indexOf(sound)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                (sound.bpm ?: 0).toString() + " · " + (sound.key?.camelot ?: "--"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            IconButton(
                                onClick = { move(index, index - 1) },
                                enabled = index > 0,
                            ) {
                                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up")
                            }
                            IconButton(
                                onClick = { move(index, index + 1) },
                                enabled = index < order.lastIndex,
                            ) {
                                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down")
                            }
                        }
                    },
                )
            }
        }
    }
}

/**
 * Greedy harmonic path: start from the most played track (or [seedId]),
 * then always pick the closest remaining track in tempo and key.
 */
internal fun harmonicOrder(sounds: List<Sound>, seedId: Long? = null): List<Sound> {
    if (sounds.size < 2) return sounds
    val remaining = sounds.toMutableList()
    val start = remaining.firstOrNull { it.id == seedId }
        ?: remaining.maxByOrNull { it.playCount }
        ?: remaining.first()
    val ordered = mutableListOf(start)
    remaining.remove(start)
    while (remaining.isNotEmpty()) {
        val last = ordered.last()
        val next = remaining.minBy { candidate -> harmonicDistance(last, candidate) }
        ordered += next
        remaining.remove(next)
    }
    return ordered
}

private fun harmonicDistance(a: Sound, b: Sound): Int {
    val bpmA = a.bpm ?: 0
    val bpmB = b.bpm ?: 0
    val tempo = if (bpmA > 0 && bpmB > 0) min(abs(bpmA - bpmB) / 4, 12) else 6
    val key = camelotDistance(a.key?.camelot, b.key?.camelot)
    // Prefer same-ish tempo first, then compatible keys; duration breaks ties.
    return tempo * 4 + key * 3 + if (b.durationMs > 0 && a.durationMs > 0) {
        (abs(b.durationMs - a.durationMs) / 60_000).toInt()
    } else {
        0
    }
}

/** Camelot wheel distance: 0 for the same key, 1 for one step or a mode change. */
internal fun camelotDistance(a: String?, b: String?): Int {
    if (a == null || b == null) return 3
    val numberA = a.takeWhile { it.isDigit() }.toIntOrNull() ?: return 3
    val numberB = b.takeWhile { it.isDigit() }.toIntOrNull() ?: return 3
    val letterA = a.lastOrNull()
    val letterB = b.lastOrNull()
    val steps = abs(numberA - numberB)
    val wheel = min(steps, 12 - steps)
    return wheel + if (letterA == letterB) 0 else 1
}