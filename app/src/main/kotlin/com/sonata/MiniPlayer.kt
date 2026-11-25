package com.sonata

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.sonata.core.designsystem.components.SoundArtwork
import com.sonata.core.designsystem.components.backdropGlass
import com.sonata.core.media.connection.PlayerConnection

/**
 * Mini player: one row, one progress line. Tapping opens the full player;
 * swiping up does the same for users who expect the gesture.
 */
@Composable
fun MiniPlayerBar(
    player: PlayerConnection,
    onOpenNowPlaying: () -> Unit,
) {
    val state by player.state.collectAsState()
    val spacing = LocalSpacing.current
    val radius = LocalRadius.current
    val sound = state.current ?: return

    Column(
        Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, dragAmount ->
                    change.consume()
                    if (dragAmount < -12) onOpenNowPlaying()
                }
            },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Dimensions.miniPlayerHeight)
                .backdropGlass(shape = radius.mdShape)
                .clickable(onClick = onOpenNowPlaying)
                .semantics { contentDescription = "Open now playing" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SoundArtwork(
                sound = sound,
                size = 44.dp,
                modifier = Modifier.padding(start = spacing.smDp),
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = spacing.smDp),
            ) {
                Text(
                    sound.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    sound.artist ?: if (state.isPlaying) "Playing" else "Paused",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { player.togglePlayPause() }) {
                Icon(
                    if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                )
            }
            IconButton(onClick = { player.next() }, modifier = Modifier.padding(end = spacing.xsDp)) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Next")
            }
        }
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            val fraction = if (state.durationMs > 0) {
                (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
            } else {
                0f
            }
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            )
        }
    }
}