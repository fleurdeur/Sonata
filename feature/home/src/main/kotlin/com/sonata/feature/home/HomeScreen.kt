package com.sonata.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.sonata.core.designsystem.Dimensions
import com.sonata.core.designsystem.LocalSpacing
import com.sonata.core.designsystem.components.EmptyState
import com.sonata.core.designsystem.components.SectionTitle
import com.sonata.core.designsystem.components.SoundArtwork
import com.sonata.core.designsystem.components.SoundRow
import com.sonata.core.domain.model.Sound
import com.sonata.core.domain.model.SoundSource
import com.sonata.core.domain.repository.BrainContext
import com.sonata.core.domain.repository.BrainRepository
import com.sonata.core.domain.repository.Settings
import com.sonata.core.domain.repository.Suggestion
import com.sonata.core.domain.repository.SettingsRepository
import com.sonata.core.domain.repository.SoundRepository
import com.sonata.core.media.connection.PlayerConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.time.LocalTime
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val soundRepository: SoundRepository,
    private val player: PlayerConnection,
    private val brainRepository: BrainRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    val sounds: StateFlow<List<Sound>> = soundRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings: StateFlow<Settings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    val suggestions: StateFlow<List<Suggestion>> = brainRepository.observeSuggestions(
        BrainContext(
            hourOfDay = LocalTime.now().hour,
            isHeadphones = false,
            isBluetooth = false,
            volumePercent = 50,
        ),
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun acceptSuggestion(suggestion: Suggestion) = viewModelScope.launch {
        brainRepository.accept(suggestion)
    }

    fun dismissSuggestion(id: String) = viewModelScope.launch {
        brainRepository.dismiss(id)
    }

    fun playAll(sounds: List<Sound>, startIndex: Int = 0) = viewModelScope.launch {
        if (sounds.isEmpty() || startIndex !in sounds.indices) return@launch
        player.play(sounds, startIndex)
        soundRepository.incrementPlayCount(sounds[startIndex].id)
    }
}

/**
 * Home: a warm greeting, one obvious thing to play, then compact shelves.
 * Every control maps to an action that exists; no placeholder cards.
 */
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenMix: () -> Unit,
    onImport: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val sounds by viewModel.sounds.collectAsState()
    val suggestions by viewModel.suggestions.collectAsState()
    val spacing = LocalSpacing.current
    val recent = remember(sounds) { sounds.sortedByDescending { it.addedAt } }
    val mostPlayed = remember(sounds) {
        sounds.filter { it.playCount > 0 }.sortedByDescending { it.playCount }.take(4)
    }
    val fromTikTok = remember(sounds) {
        sounds.filter { it.source == SoundSource.TIKTOK }.take(4)
    }
    val brainSuggestions = remember(suggestions, sounds) {
        suggestions.filter { suggestion ->
            val id = suggestion.soundId
            id != null && sounds.any { it.id == id }
        }.take(3)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = spacing.mdDp),
        contentPadding = PaddingValues(top = 8.dp, bottom = Dimensions.listBottomPadding),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item {
            HomeHeader(onOpenSearch = onOpenSearch, onOpenSettings = onOpenSettings)
        }

        if (sounds.isEmpty()) {
            item {
                EmptyState(
                    title = "Your library is waiting",
                    message = "Share a clip to Sonata or import a file. Audio, artwork and lyrics stay on this phone.",
                    primaryLabel = "Import a sound",
                    onPrimary = onImport,
                )
            }
        } else {
            item {
                ContinueCard(
                    sound = recent.first(),
                    onClick = { viewModel.playAll(recent, 0) },
                )
            }

            item { Spacer(Modifier.height(spacing.mdDp)) }

            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.smDp),
                ) {
                    QuickAction(
                        icon = Icons.Outlined.Add,
                        label = "Import",
                        modifier = Modifier.weight(1f),
                        onClick = onImport,
                    )
                    QuickAction(
                        icon = Icons.Outlined.Tune,
                        label = "Mix",
                        modifier = Modifier.weight(1f),
                        onClick = onOpenMix,
                    )
                    QuickAction(
                        icon = Icons.Filled.LibraryMusic,
                        label = "Library",
                        modifier = Modifier.weight(1f),
                        onClick = onOpenLibrary,
                    )
                }
            }

            if (brainSuggestions.isNotEmpty()) {
                item { SectionTitle("Sonata Brain") }
                items(brainSuggestions, key = { "brain-" + it.id }) { suggestion ->
                    val target = suggestion.soundId?.let { id -> sounds.firstOrNull { it.id == id } }
                    if (target != null) {
                        BrainCard(
                            suggestion = suggestion,
                            onClick = {
                                viewModel.acceptSuggestion(suggestion)
                                viewModel.playAll(listOf(target), 0)
                            },
                            onDismiss = { viewModel.dismissSuggestion(suggestion.id) },
                        )
                    }
                }
            }

            item {
                SectionTitle("Recently added") {
                    SeeAll(onClick = onOpenLibrary)
                }
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 0.dp),
                    horizontalArrangement = Arrangement.spacedBy(spacing.smDp),
                ) {
                    items(recent.take(8), key = { "recent-" + it.id }) { sound ->
                        RecentCard(
                            sound = sound,
                            onClick = {
                                val index = recent.indexOfFirst { it.id == sound.id }.coerceAtLeast(0)
                                viewModel.playAll(recent, index)
                            },
                        )
                    }
                }
            }

            if (mostPlayed.isNotEmpty()) {
                item { SectionTitle("On repeat") }
                items(mostPlayed, key = { "played-" + it.id }) { sound ->
                    SoundRow(
                        sound = sound,
                        onPlay = {
                            viewModel.playAll(mostPlayed, mostPlayed.indexOf(sound).coerceAtLeast(0))
                        },
                        onClick = {
                            viewModel.playAll(mostPlayed, mostPlayed.indexOf(sound).coerceAtLeast(0))
                        },
                    )
                }
            }

            if (fromTikTok.isNotEmpty()) {
                item { SectionTitle("From TikTok") }
                items(fromTikTok, key = { "tiktok-" + it.id }) { sound ->
                    SoundRow(
                        sound = sound,
                        onPlay = {
                            viewModel.playAll(fromTikTok, fromTikTok.indexOf(sound).coerceAtLeast(0))
                        },
                        onClick = {
                            viewModel.playAll(fromTikTok, fromTikTok.indexOf(sound).coerceAtLeast(0))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeHeader(
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "YOUR LIBRARY",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.4.sp,
            )
            Text(
                "Sonata",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
        }
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f),
        ) {
            IconButton(onClick = onOpenSearch) {
                Icon(Icons.Outlined.Search, contentDescription = "Search")
            }
        }
        Spacer(Modifier.width(8.dp))
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f),
        ) {
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Outlined.Settings, contentDescription = "Settings")
            }
        }
    }
}

@Composable
private fun ContinueCard(sound: Sound, onClick: () -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Surface(
        onClick = onClick,
        shape = shape,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.34f),
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.18f),
                        ),
                    ),
                )
                .padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cover(sound = sound, size = 96.dp, corner = 18.dp)
                Column(
                    Modifier.weight(1f).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        "LATEST IN YOUR LIBRARY",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                        letterSpacing = 1.1.sp,
                    )
                    Text(
                        sound.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        sound.artist ?: "Ready to play",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "Play " + sound.title,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun BrainCard(
    suggestion: Suggestion,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            Modifier.padding(start = spacing.mdDp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(26.dp),
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp),
            ) {
                Text(
                    suggestion.headline,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    suggestion.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Dismiss suggestion",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun QuickAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.68f),
        modifier = modifier,
    ) {
        Column(
            Modifier.padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun RecentCard(sound: Sound, onClick: () -> Unit) {
    Column(
        Modifier
            .width(142.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Play " + sound.title },
    ) {
        Cover(sound = sound, size = 142.dp, corner = 18.dp)
        Spacer(Modifier.height(8.dp))
        Text(
            sound.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            sound.artist ?: "Unknown artist",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Cover(sound: Sound, size: androidx.compose.ui.unit.Dp, corner: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .size(size)
            .shadow(10.dp, RoundedCornerShape(corner))
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        val cover = sound.coverPath
        if (cover != null && File(cover).exists()) {
            AsyncImage(
                model = File(cover),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            SoundArtwork(sound = sound, size = size)
        }
    }
}

@Composable
private fun SeeAll(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Transparent,
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "See all",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Icon(
                Icons.Filled.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(14.dp).padding(start = 4.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}