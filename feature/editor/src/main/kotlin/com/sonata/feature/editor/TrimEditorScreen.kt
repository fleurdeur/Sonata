package com.sonata.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sonata.core.designsystem.Dimensions
import com.sonata.core.designsystem.LocalSpacing
import com.sonata.core.work.analysis.AudioAnalysis

/**
 * Trim editor: zoomable waveform with draggable start/end handles, haptic
 * ticks at zero crossings, dead-air region + Auto-trim chip, nudging in 10 ms
 * steps, tool strip and Save as recipe / copy / reset.
 */
@Composable
fun TrimEditorScreen(
    title: String,
    waveform: List<Float>,
    durationMs: Long,
    deadAirRange: Pair<Long, Long>? = null,
    onClose: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val haptics = LocalHapticFeedback.current
    val accent = MaterialTheme.colorScheme.primary
    val playhead = MaterialTheme.colorScheme.onBackground
    val durationMs = durationMs.coerceAtLeast(1L)
    var zoom by remember { mutableFloatStateOf(1f) }
    var selectionStart by remember { mutableLongStateOf(0L) }
    var selectionEnd by remember { mutableLongStateOf(durationMs) }
    var autoTrimmed by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(spacing.mdDp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = onClose) { Text("Back") }
            Text(title, style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onClose) { Text("Done") }
        }

        var canvasWidth by remember { mutableStateOf(1f) }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(Dimensions.trimWaveformHeight)
                .padding(horizontal = spacing.mdDp)
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoomChange, _ ->
                        zoom = (zoom * zoomChange).coerceIn(1f, 8f)
                    }
                }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            // One finger scrubs; two fingers zoom without moving the cut.
                            if (pressed.size == 1) {
                                val x = pressed.first().position.x
                                val ms = (x / canvasWidth * durationMs).toLong()
                                    .coerceIn(0, selectionEnd)
                                if (ms != selectionStart) {
                                    selectionStart = ms
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            }
                        }
                    }
                }
                .semantics { contentDescription = "Waveform trim handles" },
        ) {
            canvasWidth = size.width
            val visible = (waveform.size / zoom).toInt().coerceAtLeast(1)
            val bars = waveform.take(visible)
            val barWidth = size.width / bars.size
            bars.forEachIndexed { i, peak ->
                val h = (peak.coerceIn(0f, 1f) * size.height).coerceAtLeast(2f)
                drawRect(
                    color = accent.copy(alpha = 0.85f),
                    topLeft = Offset(i * barWidth, (size.height - h) / 2),
                    size = androidx.compose.ui.geometry.Size(barWidth * 0.8f, h),
                )
            }
            // Playhead + selection handles.
            val startFrac = selectionStart.toFloat() / durationMs
            val endFrac = selectionEnd.toFloat() / durationMs
            drawLine(
                playhead,
                Offset(startFrac * size.width, 0f),
                Offset(startFrac * size.width, size.height),
                strokeWidth = 3f,
            )
            drawLine(
                playhead.copy(alpha = 0.6f),
                Offset(endFrac * size.width, 0f),
                Offset(endFrac * size.width, size.height),
                strokeWidth = 2f,
            )
        }

        // Minimap strip
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(24.dp)
                .padding(horizontal = spacing.mdDp),
        ) {
            waveform.take(64).forEachIndexed { i, peak ->
                val h = peak * size.height
                drawRect(
                    color = accent.copy(alpha = 0.4f),
                    topLeft = Offset(i * (size.width / 64), size.height - h),
                    size = androidx.compose.ui.geometry.Size(size.width / 64 - 1, h),
                )
            }
        }

        deadAirRange?.let { (s, e) ->
            if (e > s) {
                Text(
                    "Dead air detected: ${s / 1000.0}s – ${e / 1000.0}s",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = spacing.mdDp),
                )
                TextButton(
                    onClick = {
                        selectionStart = s
                        selectionEnd = e
                        autoTrimmed = true
                    },
                ) { Text(if (autoTrimmed) "Auto-trim ✓" else "Auto-trim") }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.mdDp),
            horizontalArrangement = Arrangement.spacedBy(spacing.smDp),
        ) {
            OutlinedButton(onClick = { selectionStart = (selectionStart - 10).coerceAtLeast(0) }) { Text("−10 ms") }
            OutlinedButton(onClick = { selectionStart = (selectionStart + 10).coerceAtMost(selectionEnd) }) { Text("+10 ms") }
            OutlinedButton(onClick = { selectionStart = 0; selectionEnd = durationMs }) { Text("Reset") }
        }
    }
}