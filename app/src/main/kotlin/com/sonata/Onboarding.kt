package com.sonata

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sonata.core.designsystem.LocalSpacing
import com.sonata.core.designsystem.components.glassSurface

private data class OnboardingStep(
    val title: String,
    val message: String,
    val actionLabel: String,
)

private val steps = listOf(
    OnboardingStep(
        "Share a sound",
        "Send any video or link to Sonata from the Share Sheet, or paste a link on Home.",
        "Import your first sound",
    ),
    OnboardingStep(
        "Trim and loop it",
        "Dead air is trimmed automatically — preview what was cut and undo it anytime.",
        "Try the trimmer",
    ),
    OnboardingStep(
        "Pick a sound",
        "The EQ engine ships with 30+ presets. Tap Try to hear Hardstyle on your clip.",
        "Open the EQ",
    ),
    OnboardingStep(
        "Build a playlist",
        "Drag to reorder, long-press to multi-select, then hit Mix for smooth transitions.",
        "Create a playlist",
    ),
)

/**
 * Four-step first-run pager with progress dots and Skip — install to first
 * saved sound in under 30 seconds.
 */
@Composable
fun OnboardingPager(onFinish: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    val spacing = LocalSpacing.current
    val stepData = steps[step]

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(spacing.lgDp)
                .glassSurface()
                .padding(spacing.lgDp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.mdDp),
        ) {
            Text(stepData.title, style = MaterialTheme.typography.headlineMedium)
            Text(
                stepData.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.xsDp)) {
                steps.forEachIndexed { i, _ ->
                    Box(
                        Modifier
                            .size(if (i == step) 12.dp else 8.dp)
                            .background(
                                if (i == step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                                CircleShape,
                            ),
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedButton(
                    onClick = onFinish,
                    modifier = Modifier.semantics { contentDescription = "Skip tutorial" },
                ) { Text("Skip") }
                Button(
                    onClick = { if (step == steps.lastIndex) onFinish() else step += 1 },
                    modifier = Modifier.semantics { contentDescription = stepData.actionLabel },
                ) { Text(if (step == steps.lastIndex) "Done" else "Next") }
            }
        }
    }
}
