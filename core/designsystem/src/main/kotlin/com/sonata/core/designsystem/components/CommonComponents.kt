package com.sonata.core.designsystem.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sonata.core.designsystem.Dimensions
import com.sonata.core.designsystem.LocalRadius

/** Branded empty state: illustration + one primary button. */
@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    primaryLabel: String? = null,
    onPrimary: (() -> Unit)? = null,
) {
    Box(modifier = modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
            WaveGlyph()
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (primaryLabel != null && onPrimary != null) {
                Button(onClick = onPrimary) { Text(primaryLabel) }
            }
        }
    }
}

@Composable
private fun WaveGlyph() {
    val transition = rememberInfiniteTransition(label = "wave")
    val y by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "y",
    )
    Box(
        Modifier
            .fillMaxWidth(0.5f)
            .height(40.dp)
            .alpha(y)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF6C5CE7)),
    )
}

/** Skeleton loader instead of spinners. */
@Composable
fun SkeletonRow(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "shimmer",
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(Dimensions.soundRowHeight)
            .padding(vertical = 8.dp)
            .clip(LocalRadius.current.smShape)
            .alpha(alpha)
            .background(MaterialTheme.colorScheme.surfaceContainer),
    )
}

/** Inline error banner with Retry. */
@Composable
fun ErrorBanner(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.material3.Surface(
        modifier = modifier.fillMaxWidth(),
        shape = LocalRadius.current.smShape,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        androidx.compose.foundation.layout.Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            androidx.compose.material3.TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}
