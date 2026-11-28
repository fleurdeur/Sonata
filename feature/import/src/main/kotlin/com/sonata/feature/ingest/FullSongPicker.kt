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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import com.sonata.core.designsystem.Spacing
import com.sonata.core.domain.model.ImportJob
import com.sonata.core.domain.model.ImportStage
import com.sonata.core.domain.model.SearchResult
import com.sonata.core.domain.repository.ImportRepository
import com.sonata.core.domain.repository.SoundRepository
import com.sonata.core.extractor.bitchord.BitchordExtractor
import com.sonata.core.work.pipeline.FullSongQueue
import com.sonata.core.work.pipeline.ImportQueue
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class FullSongPickerState(
    val soundId: Long = 0L,
    val parentJobId: String? = null,
    val query: String = "",
    val loading: Boolean = false,
    val results: List<SearchResult> = emptyList(),
    val error: String? = null,
    val attaching: Boolean = false,
    val jobId: String? = null,
    val job: ImportJob? = null,
    val searched: Boolean = false,
)

@HiltViewModel
class FullSongPickerViewModel @Inject constructor(
    private val extractor: BitchordExtractor,
    private val soundRepository: SoundRepository,
    private val importRepository: ImportRepository,
    private val queue: FullSongQueue,
    private val importQueue: ImportQueue,
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
        if (current.attaching || current.jobId != null) return
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
            result.onSuccess { jobId ->
                _state.update {
                    it.copy(attaching = false, jobId = jobId, error = null)
                }
                watchJob(jobId)
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

    /** The attach worker is the source of truth; show its real checkpoints. */
    private fun watchJob(jobId: String) {
        viewModelScope.launch {
            while (isActive) {
                val job = runCatching { importRepository.get(jobId) }.getOrNull()
                if (job == null) {
                    // The row is deleted when the user cancels the work.
                    _state.update {
                        it.copy(jobId = null, job = null, error = "The import was cancelled.")
                    }
                    return@launch
                }
                _state.update { it.copy(job = job) }
                if (job.stage.terminal) return@launch
                delay(350)
            }
        }
    }

    fun pickAgain() = _state.update {
        it.copy(jobId = null, job = null, error = null, attaching = false)
    }

    fun cancelJob() {
        val id = _state.value.jobId ?: return
        viewModelScope.launch { importQueue.cancel(id) }
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
    viewModel: FullSongPickerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val spacing = LocalSpacing.current
    LaunchedEffect(soundId) { viewModel.open(soundId, parentJobId, fallbackQuery) }

    val job = state.job
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    job?.stage == ImportStage.DONE -> "Full song ready"
                    job?.stage == ImportStage.FAILED -> "Couldn't add this recording"
                    job != null -> "Preparing the full song"
                    else -> "Choose the full song"
                },
            )
        },
        text = {
            if (job == null) {
                Column {
                    Text(
                        "Pick the exact recording. Sonata checks the audio against your clip, measures the edit, and only then replaces the clip.",
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
                        Column(Modifier.padding(top = spacing.smDp)) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(
                                "Starting the background import…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = spacing.xsDp),
                            )
                        }
                    }
                    if (state.results.isNotEmpty()) {
                        LazyColumn(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 300.dp)
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
            } else {
                AttachProgress(job, spacing, viewModel::cancelJob)
            }
        },
        confirmButton = {
            when {
                job?.stage == ImportStage.DONE -> TextButton(onClick = onDismiss) { Text("Done") }
                job?.stage == ImportStage.FAILED -> TextButton(onClick = viewModel::pickAgain) {
                    Text("Try another")
                }
                job == null -> TextButton(
                    onClick = viewModel::search,
                    enabled = !state.loading && !state.attaching,
                ) { Text("Search") }
            }
        },
        dismissButton = {
            when {
                job == null -> TextButton(onClick = onDismiss) { Text("Close") }
                job.stage == ImportStage.FAILED || job.stage == ImportStage.DONE -> Unit
                else -> TextButton(onClick = onDismiss) { Text("Run in background") }
            }
        },
    )
}

@Composable
private fun AttachProgress(job: ImportJob, spacing: Spacing, onCancel: () -> Unit) {
    val fraction = job.progress.coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth()) {
        when (job.stage) {
            ImportStage.DONE -> Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.smDp),
            ) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Column {
                    Text("Verified and applied", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "The edit was measured from the recording and the player has switched to it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            ImportStage.FAILED -> {
                Text(
                    "That upload did not pass the audio check.",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    job.error ?: "No verified match.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = spacing.xsDp),
                )
                Text(
                    "Your clip is unchanged. Try the official audio or a different upload.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = spacing.xsDp),
                )
            }

            else -> {
                Text(
                    job.title.ifBlank { "Adding the full song" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                )
                Text(
                    job.stage.label + " · " + (fraction * 100).toInt() + "%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = spacing.xsDp),
                )
                if (fraction > 0.001f) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = spacing.smDp),
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = spacing.smDp),
                    )
                }
                Text(
                    "This keeps running if you close this sheet. You can follow it in the status card.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = spacing.smDp),
                )
                job.error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = spacing.xsDp),
                    )
                    Text(
                        "Sonata will retry this stage automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.padding(top = spacing.xsDp),
                ) { Text("Cancel") }
            }
        }
    }
}

private fun formatDuration(ms: Long): String? {
    if (ms <= 0L) return null
    val seconds = ms / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
