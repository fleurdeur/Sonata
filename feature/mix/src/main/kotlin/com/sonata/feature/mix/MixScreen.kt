package com.sonata.feature.mix

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonata.core.designsystem.LocalRadius
import com.sonata.core.designsystem.LocalSpacing
import com.sonata.core.designsystem.components.glassSurface
import com.sonata.core.domain.model.MusicalKey
import com.sonata.core.domain.model.TransitionCurve
import com.sonata.core.domain.model.TransitionType
import com.sonata.core.domain.repository.MixRepository
import com.sonata.core.domain.repository.SoundRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class MixViewModel @Inject constructor(
    private val soundRepository: SoundRepository,
    private val mixRepository: MixRepository,
) : ViewModel() {

    val sounds = soundRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun autoOrder(playlistId: Long, onDone: () -> Unit = {}) = viewModelScope.launch {
        mixRepository.autoOrder(playlistId)
        onDone()
    }
}

@Composable
fun MixScreen(
    onOpenSampler: () -> Unit,
    viewModel: MixViewModel = hiltViewModel(),
) {
    val sounds by viewModel.sounds.collectAsState()
    val spacing = LocalSpacing.current
    var selectedTransition by remember { mutableStateOf<TransitionType?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.mdDp),
        verticalArrangement = Arrangement.spacedBy(spacing.mdDp),
    ) {
        Text(
            "Mix",
            style = MaterialTheme.typography.displayLarge,
            modifier = Modifier.padding(top = spacing.lgDp),
        )
        MixLandingCard("Your mixes", "Transitions saved from playlists and queues")
        MixLandingCard("Auto-Mix", "Continuously mix the whole library by BPM and key")
        MixLandingCard("Remix Radio", "Brain-powered radio from your own sounds")
        MixLandingCard("Remix Lab", "Apply Slowed + Reverb, Nightcore and more as new versions")

        Text("Track strip", style = MaterialTheme.typography.headlineMedium)
        // Horizontal waveform blocks with BPM and key badges and transition nodes.
        LazyRow(horizontalArrangement = Arrangement.spacedBy(spacing.smDp)) {
            sounds.take(8).forEachIndexed { index, sound ->
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(width = 120.dp, height = 56.dp)
                                    
                                    .glassSurface(),
                                contentAlignment = Alignment.Center,
                            ) {
                                WaveformStrip(sound.waveform.take(24))
                            }
                            if (index < 7) {
                                Text(
                                    "⇄",
                                    Modifier
                                        .clickable { selectedTransition = TransitionType.AUTO }
                                        .padding(horizontal = 6.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            sound.bpm?.let { Badge("$it BPM") }
                            sound.key?.let { Badge(it.camelot) }
                        }
                    }
                }
            }
        }

        Text("Sampler", style = MaterialTheme.typography.headlineMedium)
        SamplerGrid()

        if (selectedTransition != null) {
            TransitionSheet(
                selected = selectedTransition!!,
                onSelect = { selectedTransition = it },
                onDismiss = { selectedTransition = null },
            )
        }
    }
}


@Composable
private fun MixLandingCard(title: String, subtitle: String) {
    Surface(
        Modifier
            .fillMaxWidth()
            .clickable { },
        shape = LocalRadius.current.mdShape,
    ) {
        Column(
            Modifier
                .glassSurface()
                .padding(LocalSpacing.current.mdDp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WaveformStrip(peaks: List<Float>) {
    Canvas(Modifier.size(width = 104.dp, height = 40.dp)) {
        if (peaks.isEmpty()) return@Canvas
        val barWidth = size.width / peaks.size
        peaks.forEachIndexed { i, peak ->
            val h = (peak.coerceIn(0f, 1f) * size.height).coerceAtLeast(2f)
            drawRect(
                color = Color(0xFF6C5CE7),
                topLeft = androidx.compose.ui.geometry.Offset(i * barWidth, (size.height - h) / 2),
                size = androidx.compose.ui.geometry.Size(barWidth * 0.7f, h),
            )
        }
    }
}

@Composable
private fun Badge(text: String) {
    Text(
        text,
        Modifier
                        .glassSurface(blurDp = 6, shape = LocalRadius.current.pillShape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
    )
}

@Composable
private fun TransitionSheet(
    selected: TransitionType,
    onSelect: (TransitionType) -> Unit,
    onDismiss: () -> Unit,
) {
    var length by remember { mutableStateOf(4f) }
    var intensity by remember { mutableStateOf(0.7f) }
    Column(
        Modifier
            .fillMaxWidth()
            .glassSurface()
            .padding(LocalSpacing.current.mdDp),
        verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.smDp),
    ) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.smDp),
        ) {
            TransitionType.entries.forEach { type ->
                FilterChip(
                    selected = type == selected,
                    onClick = { onSelect(type) },
                    label = { Text(type.label) },
                )
            }
        }
        Text("Length: ${"%.1f".format(length)} s")
        androidx.compose.material3.Slider(
            value = length,
            onValueChange = { length = it },
            valueRange = 0.5f..12f,
            modifier = Modifier.semantics { contentDescription = "Transition length" },
        )
        Text("Intensity: ${"%d".format((intensity * 100).toInt())}%")
        androidx.compose.material3.Slider(
            value = intensity,
            onValueChange = { intensity = it },
            modifier = Modifier.semantics { contentDescription = "Transition intensity" },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.smDp)) {
            androidx.compose.material3.OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.semantics { contentDescription = "Close transition editor" },
            ) { Text("Preview join") }
            androidx.compose.material3.Button(
                onClick = onDismiss,
                modifier = Modifier.semantics { contentDescription = "Save transition" },
            ) { Text("Save") }
        }
    }
}

@Composable
private fun SamplerGrid() {
    val pads = (1..16).map { it }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        pads.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { pad ->
                    Box(
                        Modifier
                            .size(72.dp)
                                                        .glassSurface(blurDp = 8)
                            .clickable { },
                            contentAlignment = Alignment.Center,
                    ) {
                        Text("$pad", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
