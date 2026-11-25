package com.sonata.core.designsystem.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.sonata.core.designsystem.Dimensions
import com.sonata.core.designsystem.LocalRadius
import com.sonata.core.designsystem.LocalSpacing
import com.sonata.core.domain.model.Sound
import java.io.File

/** Screen title block: large title, optional supporting line, optional actions. */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable () -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = LocalSpacing.current.lgDp, bottom = LocalSpacing.current.smDp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        actions()
    }
}

@Composable
fun SectionTitle(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = LocalSpacing.current.lgDp, bottom = LocalSpacing.current.xsDp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        action?.invoke()
    }
}

/** Square artwork with a letter fallback; the only place covers are drawn. */
@Composable
fun SoundArtwork(sound: Sound, size: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(LocalRadius.current.xs.dp)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        val cover = sound.coverPath
        if (cover != null && File(cover).exists()) {
            AsyncImage(
                model = File(cover),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
            )
        } else {
            Text(
                sound.title.take(1).uppercase(),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A standard library row: artwork, title, one metadata line, play affordance. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SoundRow(
    sound: Sound,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = onPlay,
    onLongClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val tap = if (onLongClick != null) {
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    } else {
        Modifier.clickable(onClick = onClick)
    }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Dimensions.soundRowHeight)
            .clip(RoundedCornerShape(LocalRadius.current.sm.dp))
            .then(tap)
            .padding(horizontal = LocalSpacing.current.xsDp, vertical = LocalSpacing.current.xsDp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.smDp),
    ) {
        SoundArtwork(sound, Dimensions.soundCover)
        Column(Modifier.weight(1f)) {
            Text(
                sound.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                metadataLine(sound),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (trailing != null) {
            trailing()
        } else {
            IconButton(onClick = onPlay) {
                Icon(Icons.Outlined.GraphicEq, contentDescription = "Play ${sound.title}")
            }
        }
    }
}

/** "Artist · 2:34 · 120 BPM · 8A" — only values that exist. */
fun metadataLine(sound: Sound): String {
    val parts = buildList {
        sound.artist?.takeIf { it.isNotBlank() }?.let { add(it) }
        add(formatDuration(sound.durationMs))
        sound.bpm?.takeIf { it > 0 }?.let { add("$it BPM") }
        sound.key?.let { add(it.display) }
    }
    return parts.joinToString(" · ")
}

fun formatDuration(ms: Long): String {
    if (ms <= 0) return "--:--"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes >= 60) {
        val hours = minutes / 60
        "%d:%02d:%02d".format(hours, minutes % 60, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/** Honest empty state: one symbol, one sentence, one action. */
@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Outlined.GraphicEq,
    primaryLabel: String? = null,
    onPrimary: (() -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(LocalRadius.current.pillShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (primaryLabel != null && onPrimary != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onPrimary) { Text(primaryLabel) }
        }
    }
}

/** Inline error banner with Retry. */
@Composable
fun ErrorBanner(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = LocalRadius.current.smShape,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

/** Fixed-width spacer used between list sections. */
@Composable
fun VSpace(height: Dp) = Spacer(Modifier.height(height))

@Composable
fun HSpace(width: Dp) = Spacer(Modifier.width(width))