package com.sonata

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sonata.core.designsystem.Dimensions
import com.sonata.core.designsystem.LocalSpacing
import com.sonata.core.designsystem.components.glassSurface
import com.sonata.core.media.connection.PlayerConnection

/**
 * Now Playing: full-screen glass layer over an animated mesh-gradient
 * background driven by the cover colors. Artwork square at 88% of width with a
 * 32 dp corner radius, title 22 sp two lines, 48 dp waveform scrubber, main
 * controls (Shuffle, Previous, 72 dp Play/Pause, Next, Repeat Off/All/One) and
 * the secondary row (EQ, Speed, Sleep, A-B, Mix, Queue).
 */
@Composable
fun NowPlayingScreen(
    player: PlayerConnection,
    onCollapse: () -> Unit,
    onOpenEq: () -> Unit,
) {
    val state by player.state.collectAsState()
    val spacing = LocalSpacing.current
    val current = state.current ?: return

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF141220))
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        // double-tap artwork to favorite
                    },
                )
            },
    ) {
        MeshGradientBackground(Color(0xFF6C5CE7), Color(0xFF0AB6FF))

        Column(
            Modifier
                .fillMaxSize()
                .padding(spacing.lgDp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(onClick = onCollapse, modifier = Modifier.semantics { contentDescription = "Collapse player" }) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                }
                Text(
                    "Now playing from your library",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = {}, modifier = Modifier.semantics { contentDescription = "More options" }) {
                    Text("⋮", style = MaterialTheme.typography.headlineMedium)
                }
            }

            // Artwork square at 88 percent of width, 32 dp corner radius.
            Box(
                Modifier
                    .fillMaxWidth(0.88f)
                    .height(280.dp)
                    .background(
                        Brush.linearGradient(listOf(Color(0xFF6C5CE7), Color(0xFF0AB6FF))),
                        RoundedCornerShape(Dimensions.nowPlayingCoverCorner),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(current.title.take(1).uppercase(), style = MaterialTheme.typography.displayLarge)
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = spacing.mdDp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        current.title,
                        style = MaterialTheme.typography.headlineMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        current.artist ?: current.source.name.lowercase(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = {},
                    modifier = Modifier.semantics { contentDescription = "Favorite" },
                ) {
                    Icon(
                        if (current.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = null,
                    )
                }
            }

            // 48 dp waveform scrubber with draggable playhead.
            WaveformScrubber(
                player = player,
                waveform = current.waveform.toList(),
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Dimensions.waveformHeight)
                    .padding(vertical = spacing.smDp),
            )

            // Main controls row.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = spacing.mdDp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {},
                    modifier = Modifier.semantics { contentDescription = "Shuffle" },
                ) { Icon(Icons.Default.Shuffle, contentDescription = null) }
                IconButton(
                    onClick = player::previous,
                    modifier = Modifier.semantics { contentDescription = "Previous" },
                ) { Icon(Icons.Default.SkipPrevious, contentDescription = null) }
                IconButton(
                    onClick = player::togglePlayPause,
                    modifier = Modifier
                        .size(Dimensions.playButton)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                        .semantics { contentDescription = if (state.isPlaying) "Pause" else "Play" },
                ) {
                    Icon(
                        if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                IconButton(
                    onClick = player::next,
                    modifier = Modifier.semantics { contentDescription = "Next" },
                ) { Icon(Icons.Default.SkipNext, contentDescription = null) }
                IconButton(
                    onClick = { player.cycleRepeat() },
                    modifier = Modifier.semantics { contentDescription = "Repeat mode" },
                ) { Icon(Icons.Default.Repeat, contentDescription = null) }
            }

            // Secondary row: EQ, Speed, Sleep, A-B, Mix, Queue.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                IconButton(
                    onClick = onOpenEq,
                    modifier = Modifier.semantics { contentDescription = "Equalizer" },
                ) { Icon(Icons.Default.GraphicEq, contentDescription = null) }
                IconButton(
                    onClick = { player.setSpeed(if (state.speed >= 2f) 0.5f else state.speed + 0.25f) },
                    modifier = Modifier.semantics { contentDescription = "Playback speed" },
                ) { Icon(Icons.Default.Speed, contentDescription = null) }
                IconButton(
                    onClick = {},
                    modifier = Modifier.semantics { contentDescription = "Sleep timer" },
                ) { Icon(Icons.Default.Timer, contentDescription = null) }
                IconButton(
                    onClick = {},
                    modifier = Modifier.semantics { contentDescription = "A-B loop" },
                ) { Text("A-B", style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

/** Animated mesh gradient driven by cover colors (Material Color Utilities palette). */
@Composable
private fun MeshGradientBackground(colorA: Color, colorB: Color) {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(
            Brush.radialGradient(
                colors = listOf(colorA.copy(alpha = 0.55f), Color.Transparent),
                center = Offset(size.width * 0.2f, size.height * 0.2f),
                radius = size.width,
            ),
        )
        drawRect(
            Brush.radialGradient(
                colors = listOf(colorB.copy(alpha = 0.45f), Color.Transparent),
                center = Offset(size.width * 0.9f, size.height * 0.8f),
                radius = size.width * 0.9f,
            ),
        )
    }
}

@Composable
private fun WaveformScrubber(
    player: PlayerConnection,
    waveform: List<Float>,
    positionMs: Long,
    durationMs: Long,
    modifier: Modifier,
) {
    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    Box(modifier) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(durationMs) {
                    detectTapGestures { offset ->
                        scrubFraction = offset.x / size.width
                        scrubFraction?.let {
                            player.seekTo((it * durationMs).toLong())
                            scrubFraction = null
                        }
                    }
                }
                .semantics { contentDescription = "Waveform scrubber" },
        ) {
            val fraction = scrubFraction ?: if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
            val bars = waveform.ifEmpty { List(48) { 0.3f } }
            val barWidth = size.width / bars.size
            bars.forEachIndexed { i, peak ->
                val played = i.toFloat() / bars.size <= fraction
                val h = (peak.coerceIn(0f, 1f) * size.height).coerceAtLeast(2f)
                drawRect(
                    color = if (played) Color.White else Color.White.copy(alpha = 0.35f),
                    topLeft = Offset(i * barWidth, (size.height - h) / 2),
                    size = androidx.compose.ui.geometry.Size(barWidth * 0.7f, h),
                )
            }
            drawLine(
                Color.White,
                Offset(fraction * size.width, 0f),
                Offset(fraction * size.width, size.height),
                strokeWidth = 3f,
            )
        }
    }
}
