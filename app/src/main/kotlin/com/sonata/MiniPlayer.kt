package com.sonata

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sonata.core.designsystem.Dimensions
import com.sonata.core.designsystem.LocalRadius
import com.sonata.core.designsystem.LocalSpacing
import com.sonata.core.designsystem.components.backdropGlass
import com.sonata.core.media.connection.PlayerConnection
import com.sonata.core.media.connection.PlayerState

/**
 * Mini-player: 64 dp tall, floating 8 dp above the bar, 48 dp cover, marquee
 * title, source line, play/pause + next, 2 dp progress line along the bottom.
 * Tap or swipe up expands to Now Playing; swiping down collapses it.
 */
@Composable
fun MiniPlayerBar(
    player: PlayerConnection,
    onOpenNowPlaying: () -> Unit,
) {
    val state by player.state.collectAsState()
    val spacing = LocalSpacing.current
    val radius = LocalRadius.current
    var expanded by remember { mutableStateOf(false) }

    if (state.current == null) return

    Column(
        Modifier
            .padding(bottom = Dimensions.miniPlayerGap)
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, dragAmount ->
                    change.consume()
                    if (dragAmount < -10) onOpenNowPlaying()
                }
            },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(Dimensions.miniPlayerHeight)
                .backdropGlass(shape = radius.mdShape)
                .clickable(onClick = onOpenNowPlaying)
                .semantics { contentDescription = "Open Now Playing" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .padding(start = spacing.smDp)
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(state.current?.title?.take(1)?.uppercase() ?: "♪")
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = spacing.smDp),
            ) {
                Text(
                    state.current?.title ?: "",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    state.current?.artist ?: state.current?.source?.name?.lowercase() ?: "",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = player::togglePlayPause,
                modifier = Modifier.semantics { contentDescription = if (state.isPlaying) "Pause" else "Play" },
            ) {
                Icon(
                    if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = null,
                )
            }
            IconButton(
                onClick = player::next,
                modifier = Modifier.semantics { contentDescription = "Next track" },
            ) {
                Icon(Icons.Filled.SkipNext, contentDescription = null)
            }
        }
        // 2 dp progress line along the bottom edge.
        val progress = if (state.durationMs > 0) {
            (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
        } else 0f
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress)
                    .height(2.dp)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}
