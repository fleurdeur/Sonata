package com.sonata.feature.ingest

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonata.core.designsystem.LocalSpacing
import com.sonata.core.domain.model.SearchResult
import com.sonata.core.domain.repository.SoundRepository
import com.sonata.core.extractor.bitchord.BitchordExtractor
import com.sonata.core.work.pipeline.FullSongQueue
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FullSongPickerState(
    val soundId: Long = 0L,
    val parentJobId: String? = null,
    val query: String = "",
    val loading: Boolean = false,
    val results: List<SearchResult> = emptyList(),
    val error: String? = null,
    val attaching: Boolean = false,
    val queued: String? = null,
    val searched: Boolean = false,
)

@HiltViewModel
class FullSongPickerViewModel @Inject constructor(
    private val extractor: BitchordExtractor,
    private val soundRepository: SoundRepository,
    private val queue: FullSongQueue,
) : ViewModel() {

    private val _state = MutableStateFlow(FullSongPickerState())
    val state: StateFlow<FullSongPickerState> = _state.asStateFlow()
    private var searchJob: Job? = null

    fun open(soundId: Long, parentJobId: String?, fallbackQuery: String) {
        _state.value = FullSongPickerState(soundId = soundId, parentJobId = parentJobId)
        viewModelScope.launch {
            val sound = runCatching { soundRepository.get(soundId) }.getOrNull()
            val caption = sound?.tiktokCaption
                ?.lineSequence()
                ?.firstOrNull()
                ?.replace(Regex("""#\S+"""), " ")
                ?.replace(Regex("""\s+"""), " ")
                ?.trim()
                ?.trim('.', ',', '!', '?')
                ?.take(80)
                ?.takeIf { it.isNotBlank() }
            val artist = sound?.artist?.trim()?.takeIf { it.isNotBlank() }
            val title = sound?.title?.trim()?.takeIf {
                it.isNotBlank() && !it.startsWith("original sound", true)
            }
            val query = when {
                title == null -> listOfNotNull(caption, artist).joinToString(" ")
                title.equals(artist, ignoreCase = true) -> listOfNotNull(caption, title).joinToString(" ")
                else -> listOfNotNull(artist, title).joinToString(" ")
            }.ifBlank { fallbackQuery.trim() }
                .take(120)
            _state.update { it.copy(query = query) }
            if (query.isNotBlank()) search()
        }
    }

    fun updateQuery(value: String) = _state.update { it.copy(query = value, error = null) }

    fun search() {
        val query = _state.value.query.trim()
        if (query.length < 2) {
            _state.update { it.copy(error = "Type a song name or artist", results = emptyList()) }
            return
        }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, results = emptyList(), searched = true) }
            val hits = runCatching {
                listOf(true, false).map { music ->
                    async { runCatching { extractor.search(query, music = music) }.getOrDefault(emptyList()) }
                }.awaitAll().flatten()
            }.getOrElse { failure ->
                if (failure is CancellationException) throw failure
                emptyList()
            }
            val unique = hits.distinctBy { it.id }.take(20)
            _state.update {
                it.copy(
                    loading = false,
                    results = unique,
                    error = if (unique.isEmpty()) "No results. Try the artist too, or paste the exact title." else null,
                )
            }
        }
    }

    fun attach(candidate: SearchResult) {
        val current = _state.value
        if (current.attaching) return
        _state.update { it.copy(attaching = true, error = null) }
        viewModelScope.launch {
            val result = runCatching {
                queue.attach(
                    soundId = current.soundId,
                    parentJobId = current.parentJobId,
                    videoId = candidate.id,
                    title = candidate.title,
                    artist = candidate.author,
                )
            }
            result.onSuccess {
                _state.update { it.copy(attaching = false, queued = candidate.title) }
                delay(700)
            }.onFailure { failure ->
                if (failure is CancellationException) throw failure
                _state.update {
                    it.copy(
                        attaching = false,
                        error = failure.message ?: "Could not queue that recording",
                    )
                }
            }
        }
    }
}

/**
 * Small, explicit fallback layout: search YouTube / YouTube Music, show the
 * real results, and attach only the recording the user picked. The worker
 * still verifies it acoustically; a wrong pick never overwrites the clip.
 */
@Composable
fun FullSongPickerDialog(
    soundId: Long,
    parentJobId: String?,
    fallbackQuery: String,
    onDismiss: () -> Unit,
    onQueued: () -> Unit = {},
    viewModel: FullSongPickerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val spacing = LocalSpacing.current
    LaunchedEffect(soundId) { viewModel.open(soundId, parentJobId, fallbackQuery) }
    LaunchedEffect(state.queued) {
        if (state.queued != null) {
            onQueued()
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose the full song") },
        text = {
            Column {
                Text(
                    "Search YouTube and YouTube Music, then pick the exact recording. Sonata checks it against your clip before applying the edit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::updateQuery,
                    singleLine = true,
                    label = { Text("Song or artist") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = spacing.smDp),
                )
                if (state.loading) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = spacing.smDp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.xsDp),
                    ) {
                        CircularProgressIndicator(
                            Modifier
                                .size(18.dp)
                                .semantics { contentDescription = "Searching" },
                        )
                        Text("Searching YouTube and YouTube Music", style = MaterialTheme.typography.bodySmall)
                    }
                }
                state.error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = spacing.xsDp),
                    )
                }
                if (state.attaching) {
                    Text(
                        "Adding to the import queue…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = spacing.xsDp),
                    )
                }
                if (state.results.isNotEmpty()) {
                    LazyColumn(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .padding(top = spacing.xsDp),
                    ) {
                        items(state.results, key = { it.id }) { result ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !state.attaching) { viewModel.attach(result) }
                                    .padding(vertical = 10.dp)
                                    .semantics { contentDescription = "Use " + result.title },
                            ) {
                                Text(result.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                                Text(
                                    listOfNotNull(result.author, formatDuration(result.durationMs))
                                        .joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                HorizontalDivider(Modifier.padding(top = 8.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::search, enabled = !state.loading && !state.attaching) {
                Text("Search")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

private fun formatDuration(ms: Long): String? {
    if (ms <= 0L) return null
    val seconds = ms / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
