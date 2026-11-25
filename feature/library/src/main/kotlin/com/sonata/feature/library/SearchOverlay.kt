package com.sonata.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonata.core.brain.NaturalLanguageSearch
import com.sonata.core.designsystem.Dimensions
import com.sonata.core.designsystem.LocalSpacing
import com.sonata.core.designsystem.components.SoundRow
import com.sonata.core.domain.model.Sound
import com.sonata.core.domain.repository.SoundRepository
import com.sonata.core.media.connection.PlayerConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val soundRepository: SoundRepository,
    private val player: PlayerConnection,
) : ViewModel() {

    private val rawQuery = MutableStateFlow("")

    val query = rawQuery

    val results = rawQuery
        .debounce(120)
        .map { input ->
            if (input.isBlank()) {
                SearchState(emptyList(), null)
            } else {
                val parsed = NaturalLanguageSearch.parse(input)
                val matches = soundRepository.search(input.takeIf { parsed.text.isBlank() } ?: parsed.text)
                    .first()
                val filtered = matches.filter { sound ->
                    (parsed.bpmMin?.let { (sound.bpm ?: 0) >= it } ?: true) &&
                        (parsed.bpmMax?.let { (sound.bpm ?: 999) <= it } ?: true) &&
                        (parsed.durationMaxMs?.let { sound.durationMs <= it } ?: true) &&
                        (parsed.durationMinMs?.let { sound.durationMs >= it } ?: true) &&
                        (parsed.mood?.let { sound.mood == it } ?: true)
                }
                SearchState(filtered, parsed.understood)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchState(emptyList(), null))

    fun setQuery(text: String) { rawQuery.value = text }

    fun play(sounds: List<Sound>, index: Int) = viewModelScope.launch {
        if (sounds.isEmpty() || index !in sounds.indices) return@launch
        player.play(sounds, index)
        soundRepository.incrementPlayCount(sounds[index].id)
    }
}

data class SearchState(val sounds: List<Sound>, val understoodAs: String?)

/**
 * Search over the local library. Text matches titles, artists and tags; the
 * same field understands tempo, length and mood ("slow 120 bpm under 40s").
 */
@Composable
fun SearchOverlay(
    onClose: () -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.results.collectAsState()
    val query by viewModel.query.collectAsState()
    val spacing = LocalSpacing.current
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(Modifier.fillMaxSize().padding(horizontal = spacing.mdDp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = spacing.smDp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                placeholder = { Text("Title, artist, tag or a phrase") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focus)
                    .semantics { contentDescription = "Search field" },
                singleLine = true,
            )
        }

        state.understoodAs?.let { understood ->
            Text(
                "Understood as $understood",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = spacing.smDp),
            )
        }

        if (query.isBlank()) {
            Text(
                "Try “slow 120 bpm under 40 seconds”, an artist, or a tag from an import.",
                Modifier.padding(vertical = spacing.lgDp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (state.sounds.isEmpty()) {
            Text(
                "Nothing matches “$query”.",
                Modifier.padding(vertical = spacing.lgDp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LazyColumn(
            contentPadding = PaddingValues(bottom = Dimensions.listBottomPadding),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(state.sounds, key = { it.id }) { sound ->
                SoundRow(
                    sound = sound,
                    onPlay = { viewModel.play(state.sounds, state.sounds.indexOf(sound)) },
                    onClick = { viewModel.play(state.sounds, state.sounds.indexOf(sound)) },
                )
            }
        }
    }
}
