package com.sonata.feature.ingest

import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.sonata.core.designsystem.Dimensions
import com.sonata.core.designsystem.LocalRadius
import com.sonata.core.designsystem.LocalSpacing
import com.sonata.core.designsystem.components.backdropGlass
import com.sonata.core.designsystem.components.glassSurface
import com.sonata.core.domain.model.ImportJob
import com.sonata.core.domain.model.ImportStage
import com.sonata.core.domain.repository.ImportRepository
import com.sonata.core.work.pipeline.ImportQueue
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ImportViewModel @Inject constructor(
    private val queue: ImportQueue,
    importRepository: ImportRepository,
) : ViewModel() {

    val jobs = importRepository.observeQueue()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun importLink(url: String) = viewModelScope.launch { queue.enqueue(url, isLink = true, title = url) }
    fun importFile(uri: String) = viewModelScope.launch { queue.enqueue(uri, isLink = false) }
    fun cancel(jobId: String) = viewModelScope.launch { queue.cancel(jobId) }
}

@Composable
fun ImportSheet(
    onPickFiles: () -> Unit,
    onPickPhotos: () -> Unit,
    onRecord: () -> Unit,
    streamingEnabled: Boolean,
    onOpenStreaming: () -> Unit = {},
    viewModel: ImportViewModel = hiltViewModel(),
) {
    val jobs by viewModel.jobs.collectAsState()
    val spacing = LocalSpacing.current

    Column(
        Modifier
            .backdropGlass(shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
            .padding(spacing.mdDp),
    ) {
        Text("Import", style = MaterialTheme.typography.headlineMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.smDp)) {
            ImportTile(
                "Paste link",
                Icons.Default.Link,
                { viewModel.importLink("https://youtu.be/clip") },
                Modifier.weight(1f),
            )
            ImportTile("Files", Icons.Default.FileUpload, onPickFiles, Modifier.weight(1f))
            ImportTile("Photos", Icons.Default.PhotoLibrary, onPickPhotos, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.smDp)) {
            ImportTile("Record", Icons.Default.Mic, onRecord, Modifier.weight(1f))
            if (streamingEnabled) {
                ImportTile("From streaming", Icons.Default.CloudDownload, onOpenStreaming, Modifier.weight(1f))
            }
        }

        Text("Queue", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = spacing.mdDp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(spacing.smDp)) {
            items(jobs, key = { it.id }) { job ->
                ImportRow(
                    job = job,
                    onCancel = { viewModel.cancel(job.id) },
                )
            }
        }
    }
}

@Composable
private fun ImportTile(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .padding(vertical = 2.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
            .glassSurface()
            .padding(12.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(28.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}



@Composable
private fun ImportRow(job: ImportJob, onCancel: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(Dimensions.soundRowHeight / 1.4f)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
            .glassSurface(blurDp = 8)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(job.stageLabel(), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
        if (job.stage in listOf(ImportStage.DOWNLOADING, ImportStage.EXTRACTING, ImportStage.ANALYZING)) {
            LinearProgressIndicator(
                progress = { job.progress },
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "Import progress" },
            )
        }
        IconButton(onClick = onCancel, modifier = Modifier.semantics { contentDescription = "Cancel import" }) {
            Icon(Icons.Default.Close, contentDescription = null)
        }
        IconButton(onClick = onCancel, modifier = Modifier.semantics { contentDescription = "Retry import" }) {
            Icon(Icons.Default.Refresh, contentDescription = null)
        }
        if (job.stage == ImportStage.DONE) {
            IconButton(onClick = {}, modifier = Modifier.semantics { contentDescription = "Play imported sound" }) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
            }
            TextButton(onClick = {}) { Text("Trim") }
        }
    }
}

private fun ImportJob.stageLabel() = when (stage) {
    ImportStage.QUEUED -> "Queued"
    ImportStage.RESOLVING -> "Resolving link"
    ImportStage.DOWNLOADING -> "Downloading ${"%d".format((progress * 100).toInt())}%"
    ImportStage.EXTRACTING -> "Extracting audio"
    ImportStage.ANALYZING -> "Analyzing"
    ImportStage.DONE -> "Done"
    ImportStage.FAILED -> "Failed: ${error ?: "unknown"}"
}
