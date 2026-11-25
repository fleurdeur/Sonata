package com.sonata.feature.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonata.core.common.util.formatDuration
import com.sonata.core.designsystem.Dimensions
import com.sonata.core.designsystem.LocalSpacing
import com.sonata.core.designsystem.components.glassSurface
import androidx.compose.foundation.combinedClickable
import com.sonata.core.domain.model.MusicalKey
import com.sonata.core.domain.model.Playlist
import com.sonata.core.domain.model.Sound
import com.sonata.core.domain.model.SoundSource
import com.sonata.core.domain.repository.PlaylistRepository
import com.sonata.core.domain.repository.SoundRepository
import com.sonata.core.media.connection.PlayerConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val soundRepository: SoundRepository,
    private val playlistRepository: PlaylistRepository,
    private val player: PlayerConnection,
) : ViewModel() {

    val sounds = soundRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val playlists = playlistRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun play(sound: Sound) = viewModelScope.launch {
        player.play(listOf(sound))
        soundRepository.incrementPlayCount(sound.id)
    }

    fun playAll(sounds: List<Sound>) = viewModelScope.launch {
        if (sounds.isNotEmpty()) player.play(sounds)
    }

    fun toggleFavorite(sound: Sound) = viewModelScope.launch {
        soundRepository.setFavorite(sound.id, !sound.isFavorite)
    }

    fun deleteSelected(ids: List<Long>) = viewModelScope.launch {
        soundRepository.delete(ids)
    }

    fun addToPlaylist(playlistId: Long, ids: List<Long>) = viewModelScope.launch {
        playlistRepository.addSounds(playlistId, ids)
    }

    fun newPlaylist(name: String, onCreated: (Long) -> Unit = {}) = viewModelScope.launch {
        onCreated(playlistRepository.create(name))
    }
}

@Composable
fun LibraryScreen(
    onOpenSearch: () -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val sounds by viewModel.sounds.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val spacing = LocalSpacing.current
    var segment by remember { mutableStateOf(0) }
    var isGrid by remember { mutableStateOf(true) }
    val selected = remember { mutableStateOf(setOf<Long>()) }

    Column(Modifier.fillMaxSize().padding(horizontal = spacing.mdDp)) {
        Text(
            if (selected.value.isEmpty()) "Library" else "${selected.value.size} selected",
            style = MaterialTheme.typography.displayLarge,
            modifier = Modifier.padding(top = spacing.lgDp, bottom = spacing.mdDp),
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("Sounds", "Playlists", "Folders", "Mixes").forEachIndexed { index, label ->
                SegmentedButton(
                    selected = segment == index,
                    onClick = { segment = index },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = 4),
                ) { Text(label) }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = spacing.smDp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(selected = isGrid, onClick = { isGrid = true }, label = { Text("Grid") })
            FilterChip(selected = !isGrid, onClick = { isGrid = false }, label = { Text("List") })
        }

        if (selected.value.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip()
                    .glassSurface()
                    .padding(spacing.smDp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                IconButton(
                    { },
                    Modifier.semantics { contentDescription = "Add to playlist" },
                ) { Icon(Icons.Default.PlaylistAdd, contentDescription = null) }
                IconButton(
                    { selected.value.forEach { id -> sounds.firstOrNull { it.id == id }?.let(viewModel::toggleFavorite) } },
                    Modifier.semantics { contentDescription = "Favorite selected" },
                ) { Icon(Icons.Default.Favorite, contentDescription = null) }
                IconButton(
                    { viewModel.playAll(sounds.filter { it.id in selected.value }) },
                    Modifier.semantics { contentDescription = "Play selected" },
                ) { Icon(Icons.Default.Shuffle, contentDescription = null) }
                IconButton(
                    { viewModel.deleteSelected(selected.value.toList()); selected.value = emptySet() },
                    Modifier.semantics { contentDescription = "Delete selected" },
                ) { Icon(Icons.Default.Delete, contentDescription = null) }
            }
        }

        when (segment) {
            0 -> {
                if (isGrid) {
                    SoundGrid(sounds, selected.value) { sound ->
                        selected.value = toggle(selected.value, sound.id)
                    }
                } else {
                    SoundList(sounds, selected.value, viewModel)
                }
            }
            1 -> PlaylistGrid(playlists, viewModel)
            else -> EmptySection(segment)
        }
    }
}

private fun Modifier.clip() = this

private fun toggle(current: Set<Long>, id: Long): Set<Long> =
    if (id in current) current - id else current + id


@Composable
private fun SoundGrid(sounds: List<Sound>, selected: Set<Long>, onSelect: (Sound) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(100.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(sounds) { sound ->
            Column(
                Modifier
                    .clickable { onSelect(sound) }
                    .semantics { contentDescription = "Sound ${sound.title}" },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box {
                    LibrarySoundCover(sound)
                    if (sound.id in selected) {
                        Checkbox(checked = true, onCheckedChange = null, modifier = Modifier.align(Alignment.TopEnd))
                    }
                }
                Text(sound.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun SoundList(
    sounds: List<Sound>,
    selected: Set<Long>,
    viewModel: LibraryViewModel,
) {
    var selection = remember { selected }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(sounds, key = { it.id }) { sound ->
            SoundRow(
                sound = sound,
                isSelected = sound.id in selection,
                onRowClick = { viewModel.play(sound) },
                onLongPress = { },
                onFavorite = { viewModel.toggleFavorite(sound) },
            )
        }
    }
}

@Composable
fun SoundRow(
    sound: Sound,
    isSelected: Boolean,
    onRowClick: () -> Unit,
    onLongPress: () -> Unit,
    onFavorite: () -> Unit,
    showSelection: Boolean = true,
) {
    val spacing = LocalSpacing.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(Dimensions.soundRowHeight)
            .combinedClickable(onClick = onRowClick, onLongClick = onLongPress),  // @OptIn handled globally
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.mdDp),
    ) {
        LibrarySoundCover(sound)
        Column(Modifier.weight(1f)) {
            Text(
                sound.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                "${sound.source.name.lowercase().replaceFirstChar { it.uppercase() }} · " +
                    formatDuration(sound.durationMs) +
                    (sound.bpm?.let { " · $it BPM" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onFavorite, modifier = Modifier.semantics { contentDescription = "Favorite" }) {
            Icon(
                if (sound.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = null,
            )
        }
        if (showSelection && isSelected) {
            Checkbox(checked = true, onCheckedChange = null)
        }
    }
}

@Composable
private fun PlaylistGrid(playlists: List<Playlist>, viewModel: LibraryViewModel) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            NewPlaylistCard { viewModel.newPlaylist("New playlist") }
        }
        items(playlists, key = { it.id }) { playlist ->
            Column(
                Modifier.clickable { },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .size(Dimensions.carouselCard)
                        .background(MaterialTheme.colorScheme.surfaceContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(playlist.name.take(1), style = MaterialTheme.typography.displayLarge)
                }
                Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun NewPlaylistCard(onClick: () -> Unit) {
    Column(
        Modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(Dimensions.carouselCard)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("+", style = MaterialTheme.typography.displayLarge)
        }
        Text("New playlist", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun EmptySection(segment: Int) {
    Box(Modifier.fillMaxSize().padding(top = 40.dp), contentAlignment = Alignment.TopCenter) {
        Text("Nothing here yet", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun LibrarySoundCover(sound: Sound) {
    Box(
        Modifier
            .size(56.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(sound.title.take(1).uppercase())
    }
}
