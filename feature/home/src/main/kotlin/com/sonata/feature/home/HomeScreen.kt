package com.sonata.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonata.core.designsystem.Dimensions
import com.sonata.core.designsystem.LocalSpacing
import com.sonata.core.designsystem.components.EmptyState
import com.sonata.core.designsystem.components.ScreenHeader
import com.sonata.core.designsystem.components.SectionTitle
import com.sonata.core.designsystem.components.SoundRow
import com.sonata.core.domain.model.Sound
import com.sonata.core.domain.model.SoundSource
import com.sonata.core.domain.repository.Settings
import com.sonata.core.domain.repository.SettingsRepository
import com.sonata.core.domain.repository.SoundRepository
import com.sonata.core.media.connection.PlayerConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val soundRepository: SoundRepository,
    private val player: PlayerConnection,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    val sounds: StateFlow<List<Sound>> = soundRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings: StateFlow<Settings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    fun playAll(sounds: List<Sound>, startIndex: Int = 0) = viewModelScope.launch {
        if (sounds.isEmpty() || startIndex !in sounds.indices) return@launch
        player.play(sounds, startIndex)
        soundRepository.incrementPlayCount(sounds[startIndex].id)
    }
}

/**
 * The home screen answers one question: what can I listen to right now?
 * Recent imports first, then the sounds that actually get used, then the ones
 * that came from TikTok. Nothing is rendered that has no data behind it.
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
    val spacing = LocalSpacing.current

    val recent = sounds.sortedByDescending { it.addedAt }.take(6)
    val mostPlayed = sounds.filter { it.playCount > 0 }.sortedByDescending { it.playCount }.take(6)
    val fromTikTok = sounds.filter { it.source == SoundSource.TIKTOK }.take(6)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = spacing.mdDp),
        contentPadding = PaddingValues(bottom = Dimensions.listBottomPadding),
        verticalArrangement = Arrangement.spacedBy(spacing.xsDp),
    ) {
        item {
            ScreenHeader(
                title = "Sonata",
                subtitle = if (sounds.isEmpty()) "Your offline sound library" else "${sounds.size} sounds, all on this device",
            ) {
                IconButton(onClick = onOpenSearch, modifier = Modifier.semantics { contentDescription = "Search" }) {
                    Icon(Icons.Outlined.Search, contentDescription = null)
                }
                IconButton(onClick = onOpenSettings, modifier = Modifier.semantics { contentDescription = "Settings" }) {
                    Icon(Icons.Outlined.Settings, contentDescription = null)
                }
            }
        }

        if (sounds.isEmpty()) {
            item {
                EmptyState(
                    title = "Nothing here yet",
                    message = "Share a video from TikTok and pick Sonata. The app saves the sound, rebuilds the full track and keeps it on your phone.",
                    primaryLabel = "Import a sound",
                    onPrimary = onImport,
                )
            }
        } else {
            item {
                SectionTitle("Recently added") {
                    TextButton(onClick = onOpenLibrary) { Text("Library") }
                }
            }
            items(recent, key = { "recent-${it.id}" }) { sound ->
                SoundRow(
                    sound = sound,
                    onPlay = { viewModel.playAll(recent, recent.indexOf(sound)) },
                    onClick = { viewModel.playAll(recent, recent.indexOf(sound)) },
                )
            }

            if (mostPlayed.isNotEmpty()) {
                item {
                    SectionTitle("Most played") {
                        TextButton(onClick = onOpenMix) { Text("Mix") }
                    }
                }
                items(mostPlayed, key = { "played-${it.id}" }) { sound ->
                    SoundRow(
                        sound = sound,
                        onPlay = { viewModel.playAll(mostPlayed, mostPlayed.indexOf(sound)) },
                        onClick = { viewModel.playAll(mostPlayed, mostPlayed.indexOf(sound)) },
                    )
                }
            }

            if (fromTikTok.isNotEmpty()) {
                item { SectionTitle("From TikTok") }
                items(fromTikTok, key = { "tiktok-${it.id}" }) { sound ->
                    SoundRow(
                        sound = sound,
                        onPlay = { viewModel.playAll(fromTikTok, fromTikTok.indexOf(sound)) },
                        onClick = { viewModel.playAll(fromTikTok, fromTikTok.indexOf(sound)) },
                    )
                }
            }

            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = spacing.mdDp),
                ) {
                    TextButton(onClick = onImport) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Text("Import", Modifier.padding(start = 6.dp))
                    }
                    TextButton(onClick = onOpenLibrary) {
                        Text("Open library")
                    }
                }
            }
        }
    }
}