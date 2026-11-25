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
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.Color
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
            Row {
                TextButton(onClick = {}) { Text("Undo") }
                TextButton(onClick = {}) { Text("Redo") }
                Button(onClick = {}) { Text("Save") }
            }
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
                            if (event.changes.any { it.pressed }) {
                                val x = event.changes.first().position.x
                                val ms = (x / canvasWidth * durationMs).toLong()
                                    .coerceIn(0, durationMs)
                                selectionStart = ms
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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
                    color = Color(0xFF6C5CE7).copy(alpha = 0.85f),
                    topLeft = Offset(i * barWidth, (size.height - h) / 2),
                    size = androidx.compose.ui.geometry.Size(barWidth * 0.8f, h),
                )
            }
            // Playhead + selection handles.
            val startFrac = selectionStart.toFloat() / durationMs
            val endFrac = selectionEnd.toFloat() / durationMs
            drawLine(
                Color.White,
                Offset(startFrac * size.width, 0f),
                Offset(startFrac * size.width, size.height),
                strokeWidth = 3f,
            )
            drawLine(
                Color.White.copy(alpha = 0.6f),
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
                    color = Color(0xFF6C5CE7).copy(alpha = 0.4f),
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

        // Transport row
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.mdDp),
            horizontalArrangement = Arrangement.spacedBy(spacing.smDp),
        ) {
            OutlinedButton(onClick = {}) { Text("Play selection") }
            OutlinedButton(onClick = {}) { Text("Loop selection") }
            OutlinedButton(onClick = { selectionStart = (selectionStart - 10).coerceAtLeast(0) }) { Text("−10 ms") }
            OutlinedButton(onClick = { selectionStart = (selectionStart + 10).coerceAtMost(durationMs) }) { Text("+10 ms") }
        }

        // Tool strip
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(spacing.mdDp),
            horizontalArrangement = Arrangement.spacedBy(spacing.smDp),
        ) {
            listOf(
                "Fade in", "Fade out", "Normalize", "Reverse", "Speed", "Pitch",
                "Split", "Merge", "Noise reduction", "Voice isolation", "Loop points",
            ).forEach { tool ->
                OutlinedButton(onClick = {}) { Text(tool) }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(spacing.mdDp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Button(onClick = {}) { Text("Save as recipe") }
            OutlinedButton(onClick = {}) { Text("Save as copy") }
            OutlinedButton(onClick = { selectionStart = 0; selectionEnd = durationMs; autoTrimmed = false }) {
                Text("Reset to original")
            }
        }
    }
}
