package com.sonata

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import com.sonata.core.designsystem.Dimensions
import com.sonata.core.designsystem.LocalRadius
import com.sonata.core.designsystem.LocalSpacing
import com.sonata.core.designsystem.components.formatDuration
import com.sonata.core.designsystem.components.metadataLine
import com.sonata.core.domain.repository.SoundRepository
import com.sonata.core.media.connection.PlayerConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class NowPlayingViewModel @Inject constructor(
    private val soundRepository: SoundRepository,
) : ViewModel() {

    val favorites = soundRepository.observeAll()
        .map { sounds -> sounds.filter { it.isFavorite }.map { it.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun toggleFavorite(id: Long, favorite: Boolean) = viewModelScope.launch {
        soundRepository.setFavorite(id, !favorite)
    }
}

/**
 * Full player. Theme-aware, no fixed background: the artwork supplies the
 * colour, everything else uses the same surface and text roles as the rest of
 * the app. Controls are all wired to the session.
 */
@Composable
fun NowPlayingScreen(
    player: PlayerConnection,
    onCollapse: () -> Unit,
    onOpenEq: () -> Unit,
    viewModel: NowPlayingViewModel = hiltViewModel(),
) {
    val state by player.state.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val spacing = LocalSpacing.current
    val radius = LocalRadius.current
    val sound = state.current

    var sleepMinutes by remember { mutableIntStateOf(0) }
    var abStart by remember { mutableStateOf<Long?>(null) }
    var abEnd by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(sleepMinutes) {
        if (sleepMinutes > 0) {
            delay(sleepMinutes * 60_000L)
            if (player.state.value.isPlaying) player.togglePlayPause()
            sleepMinutes = 0
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.lgDp)
            .padding(bottom = spacing.xlDp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = spacing.smDp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCollapse) {
                Icon(Icons.Filled.ExpandMore, contentDescription = "Collapse player")
            }
            Text(
                "Now playing",
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (sound != null) {
                val favorite = sound.id in favorites
                IconButton(onClick = { viewModel.toggleFavorite(sound.id, favorite) }) {
                    Icon(
                        if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = if (favorite) "Remove from favorites" else "Add to favorites",
                        tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(spacing.lgDp))

        val coverPath = sound?.coverPath
        Box(
            Modifier
                .fillMaxWidth(0.82f)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(Dimensions.nowPlayingCoverCorner))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (coverPath != null && File(coverPath).exists()) {
                AsyncImage(
                    model = File(coverPath),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    sound?.title?.take(2)?.uppercase() ?: "♪",
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(spacing.lgDp))

        Text(
            sound?.title ?: "Nothing playing",
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            sound?.let(::metadataLine) ?: "Pick a sound from the library",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(spacing.lgDp))

        WaveformScrubber(
            waveform = sound?.waveform?.toList().orEmpty(),
            positionMs = state.positionMs,
            durationMs = state.durationMs.takeIf { it > 0 } ?: sound?.durationMs ?: 0,
            onSeek = { player.seekTo(it) },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration(state.positionMs), style = MaterialTheme.typography.labelMedium)
            Text(formatDuration(state.durationMs), style = MaterialTheme.typography.labelMedium)
        }

        Spacer(Modifier.height(spacing.mdDp))

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            IconButton(onClick = { player.cycleRepeat() }) {
                Icon(
                    if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                    contentDescription = "Repeat mode",
                    tint = if (state.repeatMode != Player.REPEAT_MODE_OFF) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            IconButton(onClick = { player.previous() }) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(36.dp))
            }
            FilledIconButton(
                onClick = { player.togglePlayPause() },
                modifier = Modifier.size(72.dp),
                shape = RoundedCornerShape(radius.pill.dp),
            ) {
                Icon(
                    if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(32.dp),
                )
            }
            IconButton(onClick = { player.next() }) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Next", modifier = Modifier.size(36.dp))
            }
            IconButton(onClick = onOpenEq) {
                Icon(Icons.Outlined.GraphicEq, contentDescription = "Equalizer")
            }
        }

        Spacer(Modifier.height(spacing.smDp))

        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(spacing.xsDp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = {
                val speeds = listOf(1f, 1.25f, 1.5f, 0.75f)
                val current = speeds.indexOfFirst { kotlin.math.abs(it - state.speed) < 0.01f }
                player.setSpeed(speeds[((current.coerceAtLeast(0)) + 1) % speeds.size])
            }) { Text("${state.speed}× speed") }

            TextButton(onClick = {
                when {
                    abStart == null -> abStart = state.positionMs
                    abEnd == null -> {
                        abEnd = state.positionMs
                        player.setAbLoop(abStart, state.positionMs)
                    }
                    else -> {
                        abStart = null
                        abEnd = null
                        player.setAbLoop(null, null)
                    }
                }
            }) {
                Text(
                    when {
                        abStart == null -> "A–B loop"
                        abEnd == null -> "Set B"
                        else -> "Clear A–B"
                    },
                )
            }

            TextButton(onClick = {
                sleepMinutes = when (sleepMinutes) {
                    0 -> 15
                    15 -> 30
                    30 -> 60
                    else -> 0
                }
            }) {
                Text(if (sleepMinutes == 0) "Sleep timer" else "${sleepMinutes} min left")
            }
        }

        if (abStart != null && abEnd == null) {
            Text(
                "A set at ${formatDuration(abStart ?: 0)} — play to the end of the phrase and tap Set B",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(spacing.xlDp))
    }
}

/** Waveform with tap-to-seek and drag-to-scrub, drawn from the stored peaks. */
@Composable
private fun WaveformScrubber(
    waveform: List<Float>,
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
) {
    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    val bars = waveform.ifEmpty { List(64) { 0.25f } }
    val playedColor = MaterialTheme.colorScheme.primary
    val restColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val cursorColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    val fraction = scrubFraction ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    fun seekAt(x: Float, width: Int) {
        if (durationMs <= 0 || width <= 0) return
        val f = (x / width).coerceIn(0f, 1f)
        scrubFraction = f
        onSeek((f * durationMs).toLong())
    }

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(Dimensions.waveformHeight)
            .pointerInput(durationMs) {
                detectTapGestures { offset -> seekAt(offset.x, size.width) }
            }
            .pointerInput(durationMs) {
                detectHorizontalDragGestures(
                    onDragEnd = { scrubFraction = null },
                    onDragCancel = { scrubFraction = null },
                ) { change, _ ->
                    change.consume()
                    seekAt(change.position.x, size.width)
                }
            }
            .semantics { contentDescription = "Seek" },
    ) {
        val count = bars.size
        val slot = size.width / count
        val barWidth = slot * 0.62f
        bars.forEachIndexed { i, peak ->
            val played = i.toFloat() / count <= fraction
            val h = (peak.coerceIn(0.02f, 1f) * size.height).coerceAtLeast(2f)
            drawRoundRect(
                color = if (played) playedColor else restColor,
                topLeft = Offset(i * slot, (size.height - h) / 2f),
                size = androidx.compose.ui.geometry.Size(barWidth, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f),
            )
        }
        drawLine(
            color = cursorColor,
            start = Offset(fraction * size.width, 0f),
            end = Offset(fraction * size.width, size.height),
            strokeWidth = 2.dp.toPx(),
        )
    }
}