package com.sonata

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lyrics
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonata.core.domain.model.Lyrics

/**
 * Apple Music-style animated lyrics: the active line grows and turns bright,
 * neighbours fade away, and the list glides to keep the current line centred.
 * Tapping any line seeks there.
 */
@Composable
fun LyricsPane(
    lyrics: Lyrics?,
    loading: Boolean,
    positionMs: Long,
    onSeek: (Long) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    clipOnly: Boolean = false,
) {
    if (loading && lyrics == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Finding lyrics",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    if (lyrics == null || lyrics.isEmpty) {
        Column(
            modifier.fillMaxSize().padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Lyrics,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                if (clipOnly) "Lyrics need the full song" else "Lyrics not found",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (clipOnly) {
                    "This is the real clip, not the full track, so a full-song lyric sheet would not line up. Import the full version to get synced lyrics."
                } else {
                    "Sonata checked LRCLIB, NetEase, QQ Music and lyrics.ovh. You can try again if the song was just released."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (!clipOnly) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onRefresh) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Search again", Modifier.padding(start = 8.dp))
                }
            }
        }
        return
    }

    if (!lyrics.synced) {
        Column(
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp),
        ) {
            Text(
                "LYRICS  ·  " + lyrics.provider.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.4.sp,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                lyrics.plainText,
                style = MaterialTheme.typography.bodyLarge,
                lineHeight = 30.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(48.dp))
        }
        return
    }

    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val active = remember(lyrics.lines, positionMs) { lyrics.indexAt(positionMs).coerceAtLeast(0) }
    LaunchedEffect(active) {
        if (active >= 0) {
            // Keep the current line near the middle of the page, not the top.
            val shift = with(density) { 170.dp.roundToPx() }
            listState.animateScrollToItem(active, scrollOffset = -shift)
        }
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "LYRICS",
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.4.sp,
            )
            Text(
                lyrics.provider,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .semantics { contentDescription = "Synced lyrics" },
            contentPadding = PaddingValues(vertical = 120.dp, horizontal = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            itemsIndexed(lyrics.lines) { index, line ->
                LyricLineRow(
                    text = line.text,
                    active = index == active,
                    distance = if (active >= 0) kotlin.math.abs(index - active) else 4,
                    onClick = { onSeek(line.timeMs) },
                )
            }
        }
    }
}

@Composable
private fun LyricLineRow(
    text: String,
    active: Boolean,
    distance: Int,
    onClick: () -> Unit,
) {
    val scale by animateFloatAsState(
        targetValue = if (active) 1.05f else 1f,
        animationSpec = tween(320),
        label = "lyricScale",
    )
    val alpha by animateFloatAsState(
        targetValue = when {
            active -> 1f
            distance == 1 -> 0.62f
            distance == 2 -> 0.40f
            else -> 0.24f
        },
        animationSpec = tween(320),
        label = "lyricAlpha",
    )
    val color by animateColorAsState(
        targetValue = if (active) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(320),
        label = "lyricColor",
    )
    Text(
        text = text,
        color = color,
        fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
        fontSize = if (active) 26.sp else 21.sp,
        lineHeight = if (active) 34.sp else 28.sp,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
    )
}