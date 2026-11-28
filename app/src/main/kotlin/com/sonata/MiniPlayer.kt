package com.sonata

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.sonata.core.designsystem.LocalSpacing
import com.sonata.core.designsystem.components.SoundArtwork
import com.sonata.core.media.connection.PlayerConnection
import kotlin.math.roundToInt

/**
 * Floating mini player. Swipe up opens the full player; swipe down fades out,
 * pauses and asks the shell for an Undo snackbar. The gesture has an explicit
 * TalkBack action because a drag is not reachable with a screen reader.
 */
@Composable
fun MiniPlayerBar(
    player: PlayerConnection,
    onOpenNowPlaying: () -> Unit,
    onDismiss: () -> Unit,
    swipeToDismiss: Boolean = true,
) {
    val state by player.state.collectAsState()
    val spacing = LocalSpacing.current
    val sound = state.current ?: return
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val shape = RoundedCornerShape(22.dp)
    val threshold = with(density) { 64.dp.toPx() }
    var drag by remember(sound.id) { mutableFloatStateOf(0f) }
    var dragging by remember(sound.id) { mutableStateOf(false) }
    val animated by animateFloatAsState(
        targetValue = if (dragging) drag else 0f,
        animationSpec = tween(180),
        label = "miniPlayerSwipe",
    )
    val fraction = if (state.durationMs > 0) {
        (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }

    fun dismiss() {
        if (!swipeToDismiss) {
            drag = 0f
            return
        }
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        onDismiss()
    }

    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.smDp)
            .offset { IntOffset(0, animated.roundToInt()) }
            .alpha((1f - drag / (threshold * 2.4f)).coerceIn(0.45f, 1f))
            .shadow(12.dp, shape)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .pointerInput(sound.id, swipeToDismiss) {
                detectVerticalDragGestures(
                    onDragStart = { dragging = true },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        drag = if (dragAmount < 0) {
                            // Swipe up is an open gesture, not a drag offset.
                            if (dragAmount < -14) onOpenNowPlaying()
                            0f
                        } else {
                            (drag + dragAmount).coerceIn(0f, threshold * 2.4f)
                        }
                    },
                    onDragEnd = {
                        dragging = false
                        if (drag >= threshold) dismiss() else drag = 0f
                    },
                    onDragCancel = {
                        dragging = false
                        drag = 0f
                    },
                )
            }
            .clickable(onClick = onOpenNowPlaying)
            .semantics {
                contentDescription = "Mini player: " + sound.title +
                    (sound.artist?.let { " by " + it } ?: "")
                customActions = listOf(
                    CustomAccessibilityAction("Dismiss and stop playback") {
                        dismiss()
                        true
                    },
                )
            },
    ) {
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                            MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                    ),
                ),
        )
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 68.dp)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SoundArtwork(sound = sound, size = 50.dp)
                Column(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                ) {
                    Text(
                        sound.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        sound.artist ?: if (state.isPlaying) "Playing" else "Paused",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledIconButton(
                    onClick = { player.togglePlayPause() },
                    modifier = Modifier.padding(end = 2.dp),
                ) {
                    Icon(
                        if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                    )
                }
                IconButton(onClick = { player.next() }) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Next")
                }
            }
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
            )
        }
    }
}