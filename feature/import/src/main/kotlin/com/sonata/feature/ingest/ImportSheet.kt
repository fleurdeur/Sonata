package com.sonata.feature.ingest

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonata.core.designsystem.LocalRadius
import com.sonata.core.designsystem.LocalSpacing
import com.sonata.core.domain.model.ImportJob
import com.sonata.core.domain.model.ImportStage
import com.sonata.core.domain.repository.ImportRepository
import com.sonata.core.work.pipeline.ImportQueue
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.delay
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

    fun importLink(url: String) = viewModelScope.launch {
        queue.enqueue(url.trim(), isLink = true, title = url.trim())
    }

    fun importFile(uri: String) = viewModelScope.launch { queue.enqueue(uri, isLink = false) }

    fun cancel(jobId: String) = viewModelScope.launch { queue.cancel(jobId) }
}

/**
 * Import sheet: every row performs a real action. Files and videos are picked
 * with the system document picker, links are validated and handed to the
 * TikTok/YouTube resolver chain, recording writes an AAC file and enqueues it.
 * The queue shows the actual pipeline stage, progress and failure reason.
 */
@Composable
fun ImportSheet(
    onClose: () -> Unit,
    onOpenSound: (Long) -> Unit,
    viewModel: ImportViewModel = hiltViewModel(),
) {
    val jobs by viewModel.jobs.collectAsState()
    val spacing = LocalSpacing.current
    val radius = LocalRadius.current
    val context = LocalContext.current

    var showLinkDialog by remember { mutableStateOf(false) }
    var linkText by remember { mutableStateOf("") }
    var recording by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordingFile by remember { mutableStateOf<File?>(null) }
    var recordingSeconds by remember { mutableStateOf(0) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching { context.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            viewModel.importFile(it.toString())
        }
    }

    val startRecording = {
        val file = File(context.cacheDir, "record-${System.currentTimeMillis()}.m4a")
        runCatching {
            val recorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioChannels(1)
            recorder.setAudioSamplingRate(44_100)
            recorder.setAudioEncodingBitRate(128_000)
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()
            recorder.start()
            recording = recorder
            recordingFile = file
            recordingSeconds = 0
        }.onFailure {
            android.widget.Toast.makeText(context, "Could not start recording", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording()
        else android.widget.Toast.makeText(context, "Microphone permission is needed to record", android.widget.Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(recording) {
        if (recording != null) {
            while (recording != null) {
                delay(1_000)
                recordingSeconds += 1
            }
        }
    }

    val stopRecording: () -> Unit = {
        recording?.let { recorder ->
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
            recording = null
            recordingFile?.let { file ->
                if (file.exists() && file.length() > 0) viewModel.importFile(Uri.fromFile(file).toString()) else file.delete()
            }
            recordingFile = null
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.mdDp, vertical = spacing.smDp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Import", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Everything stays on this device",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close import sheet")
            }
        }

        if (recording != null) {
            Surface(
                Modifier
                    .fillMaxWidth()
                    .padding(top = spacing.smDp),
                shape = radius.mdShape,
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ) {
                Row(
                    Modifier.padding(spacing.mdDp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error),
                    )
                    Text(
                        "Recording  ${"%d:%02d".format(recordingSeconds / 60, recordingSeconds % 60)}",
                        Modifier
                            .weight(1f)
                            .padding(start = spacing.smDp),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    FilledTonalButton(onClick = stopRecording) {
                        Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Stop", Modifier.padding(start = 6.dp))
                    }
                }
            }
        }

        ImportAction(
            icon = Icons.Filled.Movie,
            title = "Audio or video file",
            subtitle = "From storage, a video is converted to audio",
            onClick = { filePicker.launch(arrayOf("audio/*", "video/*")) },
        )
        ImportAction(
            icon = Icons.Filled.Link,
            title = "Paste a link",
            subtitle = "TikTok, direct audio; the sound becomes a full track",
            onClick = { linkText = ""; showLinkDialog = true },
        )
        ImportAction(
            icon = Icons.Filled.Mic,
            title = "Record a sound",
            subtitle = "Microphone, saved as AAC on this device",
            onClick = {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                if (granted) startRecording() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
            },
        )

        if (jobs.isNotEmpty()) {
            Text(
                "Queue",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = spacing.mdDp, bottom = spacing.xsDp),
            )
            LazyColumn(
                Modifier.heightIn(max = 260.dp),
                verticalArrangement = Arrangement.spacedBy(spacing.xsDp),
            ) {
                items(jobs, key = { it.id }) { job ->
                    ImportRow(
                        job = job,
                        onCancel = { viewModel.cancel(job.id) },
                        onOpen = { job.soundId?.let { id -> onOpenSound(id) } },
                    )
                }
            }
        }
    }

    if (showLinkDialog) {
        AlertDialog(
            onDismissRequest = { showLinkDialog = false },
            title = { Text("Paste a link") },
            text = {
                Column {
                    OutlinedTextField(
                        value = linkText,
                        onValueChange = { linkText = it },
                        singleLine = true,
                        label = { Text("https://…") },
                    )
                    Text(
                        "TikTok shares work without logging in. The app reads the post, separates the audio and rebuilds the full track.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = spacing.smDp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = linkText.isNotBlank(),
                    onClick = {
                        viewModel.importLink(linkText)
                        showLinkDialog = false
                    },
                ) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = { showLinkDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ImportAction(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = spacing.smDp)
            .clip(RoundedCornerShape(LocalRadius.current.sm.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = spacing.smDp, vertical = 12.dp)
            .semantics { contentDescription = title },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = spacing.smDp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ImportRow(job: ImportJob, onCancel: () -> Unit, onOpen: () -> Unit) {
    val spacing = LocalSpacing.current
    val finished = job.stage == ImportStage.DONE
    val failed = job.stage == ImportStage.FAILED
    Surface(
        Modifier.fillMaxWidth(),
        shape = LocalRadius.current.smShape,
        color = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(horizontal = spacing.mdDp, vertical = spacing.smDp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    job.title.ifBlank { "Import" },
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                )
                if (finished) {
                    IconButton(onClick = onOpen) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Open imported sound")
                    }
                } else {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Filled.Close, contentDescription = "Cancel import")
                    }
                }
            }
            Text(
                job.stageLabel(),
                style = MaterialTheme.typography.bodySmall,
                color = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!finished && !failed) {
                LinearProgressIndicator(
                    progress = { job.progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = spacing.xsDp)
                        .semantics { contentDescription = "Import progress" },
                )
            }
        }
    }
}

private fun ImportJob.stageLabel(): String = when (stage) {
    ImportStage.QUEUED -> error?.let { "Waiting to retry: $it" } ?: "Queued"
    ImportStage.RESOLVING -> "Reading the link"
    ImportStage.DOWNLOADING -> "Downloading ${(progress * 100).toInt()}%"
    ImportStage.EXTRACTING -> "Separating audio"
    ImportStage.ANALYZING -> "Measuring tempo, key and levels"
    ImportStage.RENDERING -> "Rendering the full track"
    ImportStage.DONE -> "Ready · ${(progress * 100).toInt()}%"
    ImportStage.FAILED -> error ?: "Import failed"
}