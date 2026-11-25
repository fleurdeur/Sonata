package com.sonata.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonata.core.brain.NaturalLanguageSearch
import com.sonata.core.designsystem.LocalSpacing
import com.sonata.core.designsystem.components.glassSurface
import com.sonata.core.domain.model.Sound
import com.sonata.core.domain.repository.SoundRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val soundRepository: SoundRepository,
) : ViewModel() {

    private val rawQuery = MutableStateFlow("")

    val query: kotlinx.coroutines.flow.StateFlow<String> = rawQuery

    val results = rawQuery
        .debounce(120)
        .map { input ->
            if (input.isBlank()) {
                SearchState(emptyList(), null)
            } else {
                val parsed = NaturalLanguageSearch.parse(input)
                val matches = soundRepository.search(input.takeIf { parsed.text.isBlank() } ?: parsed.text)
                    .first()
                val filtered = matches.filter { s ->
                    (parsed.bpmMin?.let { (s.bpm ?: 0) >= it } ?: true) &&
                        (parsed.bpmMax?.let { (s.bpm ?: 999) <= it } ?: true) &&
                        (parsed.durationMaxMs?.let { s.durationMs <= it } ?: true) &&
                        (parsed.durationMinMs?.let { s.durationMs >= it } ?: true) &&
                        (parsed.mood?.let { s.mood == it } ?: true)
                }
                SearchState(filtered, parsed.understood)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchState(emptyList(), null))

    fun setQuery(text: String) {
        rawQuery.value = text
    }
}

data class SearchState(val sounds: List<Sound>, val understoodAs: String?)

/** Full-screen glass search overlay with live grouped results and an
 *  "Understood as:" chip row for natural-language queries. */
@Composable
fun SearchOverlay(viewModel: SearchViewModel = hiltViewModel()) {
    val state by viewModel.results.collectAsState()
    val query by viewModel.query.collectAsState()
    val spacing = LocalSpacing.current
    var filter by remember { mutableStateOf("Sounds") }

    Column(
        Modifier
            .fillMaxSize()
            .glassSurface(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(spacing.mdDp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.smDp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                placeholder = { Text("Search sounds, playlists, tags…") },
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "Search field" },
                singleLine = true,
            )
            IconButton(
                onClick = {},
                modifier = Modifier.semantics { contentDescription = "Voice search" },
            ) { Icon(Icons.Default.Mic, contentDescription = null) }
            IconButton(
                onClick = { viewModel.setQuery("") },
                modifier = Modifier.semantics { contentDescription = "Close search" },
            ) { Icon(Icons.Default.Close, contentDescription = null) }
        }

        Row(
            Modifier.padding(horizontal = spacing.mdDp),
            horizontalArrangement = Arrangement.spacedBy(spacing.smDp),
        ) {
            listOf("Sounds", "Playlists", "Streaming", "Tags").forEach { f ->
                FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f) })
            }
        }

        state.understoodAs?.let {
            Text(
                "Understood as: $it",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(spacing.mdDp),
            )
        }

        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(horizontal = spacing.mdDp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(state.sounds, key = { it.id }) { sound ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.mdDp),
                ) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainer, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(sound.title.take(1))
                    }
                    Column {
                        Text(sound.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            sound.artist ?: sound.source.name.lowercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (state.sounds.isEmpty()) {
                item {
                    Text(
                        "No results yet — try \"slow sad songs under 30 seconds\"",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
        }
    }
}
