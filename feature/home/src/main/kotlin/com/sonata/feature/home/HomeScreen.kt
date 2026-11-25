package com.sonata.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonata.core.designsystem.Dimensions
import com.sonata.core.designsystem.LocalRadius
import com.sonata.core.designsystem.LocalSpacing
import com.sonata.core.designsystem.components.glassSurface
import com.sonata.core.domain.model.Sound
import com.sonata.core.domain.repository.Settings
import com.sonata.core.domain.repository.BrainRepository
import com.sonata.core.domain.repository.SettingsRepository
import com.sonata.core.domain.repository.SoundRepository
import com.sonata.core.domain.repository.Suggestion
import com.sonata.core.media.connection.PlayerConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Calendar
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class HomeViewModel @Inject constructor(
    soundRepository: SoundRepository,
    private val player: PlayerConnection,
    private val brain: BrainRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    val sounds = soundRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val suggestions: StateFlow<List<Suggestion>> = brain.observeSuggestions(
        com.sonata.core.domain.repository.BrainContext(
            hourOfDay = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
            isHeadphones = false,
            isBluetooth = false,
            volumePercent = 60,
        ),
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    private val _quickFilter = MutableStateFlow(QuickFilter.ALL)
    val quickFilter: StateFlow<QuickFilter> = _quickFilter.asStateFlow()

    fun selectFilter(filter: QuickFilter) {
        _quickFilter.value = filter
    }

    fun play(sound: Sound) = viewModelScope.launch {
        player.play(listOf(sound))
        brain.accept(Suggestion("accept_play", "", "", soundId = sound.id))
    }

    fun dismiss(suggestion: Suggestion) = viewModelScope.launch {
        brain.dismiss(suggestion.id)
    }
}

enum class QuickFilter(val label: String) {
    ALL("All"), RECENT("Recent"), FAVORITES("Favorites"), LOOPS("Loops"), TRENDING("Trending sources");
}

private fun greeting(hour: Int) = when (hour) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    in 18..22 -> "Good evening"
    else -> "Good night"
}

@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenMix: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val sounds by viewModel.sounds.collectAsState()
    val suggestions by viewModel.suggestions.collectAsState()
    val filter by viewModel.quickFilter.collectAsState()
    val spacing = LocalSpacing.current
    val radius = LocalRadius.current
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

    val filtered = when (filter) {
        QuickFilter.ALL -> sounds
        QuickFilter.RECENT -> sounds.sortedByDescending { it.addedAt }.take(20)
        QuickFilter.FAVORITES -> sounds.filter { it.isFavorite }
        QuickFilter.LOOPS -> sounds.sortedByDescending { it.loopCount }
        QuickFilter.TRENDING -> sounds.sortedByDescending { it.playCount }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = spacing.mdDp),
        verticalArrangement = Arrangement.spacedBy(spacing.mdDp),
    ) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = spacing.lgDp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(greeting(hour), style = MaterialTheme.typography.displayLarge)
                }
                IconButton(onClick = onOpenSearch, modifier = Modifier.semantics { contentDescription = "Search" }) {
                    Icon(Icons.Default.Search, contentDescription = "Search")
                }
                IconButton(onClick = onOpenSettings, modifier = Modifier.semantics { contentDescription = "Settings" }) {
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("S", color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
        }
        item {
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(spacing.smDp),
            ) {
                QuickFilter.entries.forEach { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { viewModel.selectFilter(f) },
                        label = { Text(f.label) },
                    )
                }
            }
        }
        // "Continue" hero card, full width, 16:9 cover.
        item {
            val hero = filtered.firstOrNull()
            if (hero != null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(radius.mdShape)
                        .glassSurface(),
                ) {
                    Column(
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(spacing.mdDp),
                    ) {
                        Text(
                            "Continue · ${hero.title}",
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(spacing.smDp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { viewModel.play(hero) },
                                modifier = Modifier
                                    .semantics { contentDescription = "Play ${hero.title}" },
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                            }
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                        }
                    }
                }
            }
        }
        item { SectionHeader("For you", onOpenLibrary) }
        item {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(spacing.mdDp),
            ) {
                filtered.take(10).forEach { sound ->
                    SoundCard(sound, Modifier.size(Dimensions.carouselCard), onClick = { viewModel.play(sound) })
                }
            }
        }
        item {
            FreshMixCard(
                onPlay = { filtered.firstOrNull()?.let(viewModel::play) },
                onOpenMix = onOpenMix,
            )
        }
        item { SectionHeader("Recently added", onOpenLibrary) }
        item {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(spacing.mdDp),
            ) {
                filtered.sortedByDescending { it.addedAt }.take(10).forEach { sound ->
                    SoundCard(sound, Modifier.size(Dimensions.carouselCard), onClick = { viewModel.play(sound) })
                }
            }
        }
        item { SectionHeader("Forgotten favorites", onOpenLibrary) }
        item {
            val forgotten = filtered.filter { it.isFavorite && it.playCount <= 2 }
            if (forgotten.isEmpty()) {
                Text("Nothing forgotten — you revisit everything you love.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(spacing.mdDp),
                ) {
                    forgotten.take(10).forEach { sound ->
                        SoundCard(sound, Modifier.size(Dimensions.carouselCard), onClick = { viewModel.play(sound) })
                    }
                }
            }
        }
        item { SectionHeader("Loops you play most", onOpenLibrary) }
        items(filtered.sortedByDescending { it.loopCount }.take(5)) { sound ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(Dimensions.soundRowHeight)
                    .clickable { viewModel.play(sound) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.mdDp),
            ) {
                SoundCover(sound, size = Dimensions.soundCover)
                Text(sound.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Badge(icon = Icons.Default.Loop, text = "${sound.loopCount}")
            }
        }
        item { SectionHeader("Suggestions", onOpenLibrary) }
        items(suggestions) { suggestion ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(radius.pillShape)
                    .glassSurface(blurDp = 10, shape = radius.pillShape)
                    .padding(horizontal = spacing.mdDp, vertical = spacing.smDp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.smDp),
            ) {
                Text("✦", color = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(suggestion.headline, style = MaterialTheme.typography.titleLarge)
                    Text(
                        suggestion.reason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { viewModel.dismiss(suggestion) }) {
                    Icon(Icons.Default.Favorite, contentDescription = "Dismiss suggestion")
                }
            }
        }
        item { Spacer(Modifier.height(180.dp)) }
    }
}

@Composable
private fun SectionHeader(title: String, onSeeAll: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
        Text(
            "See all",
            Modifier.clickable(onClick = onSeeAll),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
fun SoundCover(sound: Sound, size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .size(size)
            .clip(LocalRadius.current.smShape)
            .background(MaterialTheme.colorScheme.surfaceContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(sound.title.take(1).uppercase(), style = MaterialTheme.typography.headlineMedium)
    }
}

@Composable
private fun SoundCard(sound: Sound, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick)) {
        SoundCover(sound, size = Dimensions.carouselCard)
        Spacer(Modifier.height(LocalSpacing.current.xsDp))
        Text(sound.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun FreshMixCard(onPlay: () -> Unit, onOpenMix: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(LocalRadius.current.mdShape)
            .glassSurface()
            .padding(LocalSpacing.current.mdDp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Fresh Mix", style = MaterialTheme.typography.headlineMedium)
                Text("Daily picks from Sonata Brain", style = MaterialTheme.typography.labelSmall)
            }
            IconButton(onClick = onPlay, modifier = Modifier.semantics { contentDescription = "Play Fresh Mix" }) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
            }
            IconButton(onClick = onOpenMix, modifier = Modifier.semantics { contentDescription = "Open Mix" }) {
                Text("Mix", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun Badge(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
        Text(text, style = MaterialTheme.typography.labelSmall)
    }
}
