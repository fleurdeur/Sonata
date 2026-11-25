package com.sonata.feature.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.sonata.core.designsystem.components.EmptyState
import com.sonata.core.designsystem.components.ScreenHeader
import com.sonata.core.designsystem.components.SoundArtwork
import com.sonata.core.designsystem.components.SoundRow
import com.sonata.core.designsystem.components.formatDuration
import com.sonata.core.domain.model.Playlist
import com.sonata.core.domain.model.Sound
import com.sonata.core.domain.repository.PlaylistRepository
import com.sonata.core.domain.repository.SoundRepository
import com.sonata.core.media.connection.PlayerConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class LibrarySort(val label: String) {
    RECENT("Recently added"),
    TITLE("Title"),
    PLAYED("Most played"),
    BPM("Tempo"),
}

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val soundRepository: SoundRepository,
    private val playlistRepository: PlaylistRepository,
    private val player: PlayerConnection,
) : ViewModel() {

    val sounds: StateFlow<List<Sound>> = soundRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val playlists: StateFlow<List<Playlist>> = playlistRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _openPlaylist = MutableStateFlow<Long?>(null)
    val openPlaylist: StateFlow<Long?> = _openPlaylist.asStateFlow()

    val playlistSounds: StateFlow<List<Sound>> = _openPlaylist
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else playlistRepository.observeSounds(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun openPlaylist(id: Long?) { _openPlaylist.value = id }

    fun play(sounds: List<Sound>, index: Int = 0) = viewModelScope.launch {
        if (sounds.isEmpty()) return@launch
        player.play(sounds, index)
        sounds.getOrNull(index)?.let { soundRepository.incrementPlayCount(it.id) }
    }

    fun toggleFavorite(sound: Sound) = viewModelScope.launch {
        soundRepository.setFavorite(sound.id, !sound.isFavorite)
    }

    fun favoriteAll(sounds: List<Sound>, favorite: Boolean) = viewModelScope.launch {
        sounds.forEach { soundRepository.setFavorite(it.id, favorite) }
    }

    fun delete(ids: List<Long>) = viewModelScope.launch {
        soundRepository.delete(ids)
    }

    fun addToPlaylist(playlistId: Long, ids: List<Long>) = viewModelScope.launch {
        playlistRepository.addSounds(playlistId, ids)
    }

    fun createPlaylist(name: String) = viewModelScope.launch {
        if (name.isNotBlank()) playlistRepository.create(name.trim())
    }

    fun deletePlaylist(id: Long) = viewModelScope.launch {
        playlistRepository.delete(id)
        if (_openPlaylist.value == id) _openPlaylist.value = null
    }
}

/**
 * Library: everything that is actually importable, in one list. Segments are
 * limited to the two things the app stores (sounds, playlists); selection,
 * sorting and playlist membership are wired to the repositories.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    onOpenSearch: () -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val sounds by viewModel.sounds.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val openPlaylist by viewModel.openPlaylist.collectAsState()
    val playlistSounds by viewModel.playlistSounds.collectAsState()
    val spacing = LocalSpacing.current
    val radius = LocalRadius.current

    var tab by remember { mutableStateOf(0) }
    var sort by remember { mutableStateOf(LibrarySort.RECENT) }
    var sortMenu by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(setOf<Long>()) }
    var showNewPlaylist by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var playlistPickerFor by remember { mutableStateOf<List<Long>?>(null) }

    val sorted = remember(sounds, sort) {
        when (sort) {
            LibrarySort.RECENT -> sounds.sortedByDescending { it.addedAt }
            LibrarySort.TITLE -> sounds.sortedBy { it.title.lowercase() }
            LibrarySort.PLAYED -> sounds.sortedByDescending { it.playCount }
            LibrarySort.BPM -> sounds.sortedBy { it.bpm ?: 0 }
        }
    }

    val currentPlaylist = playlists.firstOrNull { it.id == openPlaylist }

    if (currentPlaylist != null) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = spacing.mdDp),
        ) {
            ScreenHeader(title = currentPlaylist.name, subtitle = "${playlistSounds.size} sounds · ${formatDuration(currentPlaylist.totalDurationMs)}") {
                IconButton(onClick = { viewModel.openPlaylist(null) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to library")
                }
                IconButton(onClick = { viewModel.play(playlistSounds) }) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Play playlist")
                }
                IconButton(onClick = { viewModel.deletePlaylist(currentPlaylist.id) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete playlist")
                }
            }
            if (playlistSounds.isEmpty()) {
                EmptyState(
                    title = "This playlist is empty",
                    message = "Select sounds in the library and add them here.",
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = Dimensions.listBottomPadding),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(playlistSounds, key = { it.id }) { sound ->
                        SoundRow(
                            sound = sound,
                            onPlay = { viewModel.play(playlistSounds, playlistSounds.indexOf(sound)) },
                            onClick = { viewModel.play(playlistSounds, playlistSounds.indexOf(sound)) },
                        )
                    }
                }
            }
        }
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = spacing.mdDp),
    ) {
        ScreenHeader(
            title = if (selection.isEmpty()) "Library" else "${selection.size} selected",
            subtitle = if (selection.isEmpty()) "${sounds.size} sounds · ${playlists.size} playlists" else null,
        ) {
            IconButton(onClick = onOpenSearch, modifier = Modifier.semantics { contentDescription = "Search" }) {
                Icon(Icons.Outlined.Search, contentDescription = null)
            }
            Box {
                IconButton(onClick = { sortMenu = true }, modifier = Modifier.semantics { contentDescription = "Sort" }) {
                    Icon(Icons.Outlined.Sort, contentDescription = null)
                }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    LibrarySort.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = { sort = option; sortMenu = false },
                            trailingIcon = {
                                if (sort == option) Icon(Icons.Filled.Check, contentDescription = null)
                            },
                        )
                    }
                }
            }
        }

        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Sounds") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Playlists") })
        }

        if (selection.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = spacing.smDp)
                    .clip(radius.smShape)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = spacing.xsDp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    viewModel.play(sorted.filter { it.id in selection })
                }) { Icon(Icons.Filled.PlayArrow, contentDescription = "Play selected") }
                IconButton(onClick = {
                    viewModel.favoriteAll(sorted.filter { it.id in selection }, favorite = true)
                }) { Icon(Icons.Filled.Favorite, contentDescription = "Favorite selected") }
                IconButton(onClick = { playlistPickerFor = selection.toList() }) {
                    Icon(Icons.Outlined.PlaylistAdd, contentDescription = "Add selected to playlist")
                }
                IconButton(onClick = {
                    viewModel.delete(selection.toList())
                    selection = emptySet()
                }) { Icon(Icons.Filled.Delete, contentDescription = "Delete selected") }
                TextButton(onClick = { selection = emptySet() }) { Text("Done") }
            }
        }

        when (tab) {
            0 -> if (sorted.isEmpty()) {
                EmptyState(
                    title = "No sounds yet",
                    message = "Import a file or share a TikTok video to Sonata.",
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = Dimensions.listBottomPadding),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(sorted, key = { it.id }) { sound ->
                        SoundRow(
                            sound = sound,
                            onPlay = { viewModel.play(sorted, sorted.indexOf(sound)) },
                            onClick = { viewModel.play(sorted, sorted.indexOf(sound)) },
                            onLongClick = {
                                selection = if (sound.id in selection) selection - sound.id else selection + sound.id
                            },
                            modifier = Modifier.background(
                                if (sound.id in selection) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                else androidx.compose.ui.graphics.Color.Transparent,
                                RoundedCornerShape(radius.sm.dp),
                            ),
                            trailing = if (sound.id in selection) {
                                {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(end = spacing.smDp),
                                    )
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
            }

            else -> LazyColumn(
                contentPadding = PaddingValues(top = spacing.smDp, bottom = Dimensions.listBottomPadding),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(Dimensions.soundRowHeight)
                            .clip(radius.smShape)
                            .clickable { newPlaylistName = ""; showNewPlaylist = true }
                            .padding(horizontal = spacing.smDp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(Dimensions.soundCover)
                                .clip(RoundedCornerShape(radius.xs.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Outlined.Add,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        Text("New playlist", Modifier.padding(start = spacing.smDp))
                    }
                }
                if (playlists.isEmpty()) {
                    item {
                        Text(
                            "Playlists group sounds you use together.",
                            Modifier.padding(spacing.mdDp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                items(playlists, key = { it.id }) { playlist ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(Dimensions.soundRowHeight)
                            .clip(radius.smShape)
                            .clickable { viewModel.openPlaylist(playlist.id) }
                            .padding(horizontal = spacing.smDp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(Dimensions.soundCover)
                                .clip(RoundedCornerShape(radius.xs.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                playlist.name.take(1).uppercase(),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Column(
                            Modifier
                                .weight(1f)
                                .padding(start = spacing.smDp),
                        ) {
                            Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${playlist.soundCount} sounds · ${formatDuration(playlist.totalDurationMs)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { viewModel.deletePlaylist(playlist.id) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete ${playlist.name}")
                        }
                    }
                }
            }
        }
    }

    if (showNewPlaylist) {
        AlertDialog(
            onDismissRequest = { showNewPlaylist = false },
            title = { Text("New playlist") },
            text = {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    singleLine = true,
                    label = { Text("Name") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.createPlaylist(newPlaylistName)
                        showNewPlaylist = false
                    },
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showNewPlaylist = false }) { Text("Cancel") }
            },
        )
    }

    val targets = playlistPickerFor
    if (targets != null) {
        AlertDialog(
            onDismissRequest = { playlistPickerFor = null },
            title = { Text("Add to playlist") },
            text = {
                Column {
                    if (playlists.isEmpty()) {
                        Text("Create a playlist first from the Playlists tab.")
                    }
                    playlists.forEach { playlist ->
                        TextButton(
                            onClick = {
                                viewModel.addToPlaylist(playlist.id, targets)
                                playlistPickerFor = null
                                selection = emptySet()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(playlist.name, Modifier.weight(1f))
                            Text("${playlist.soundCount}")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { playlistPickerFor = null }) { Text("Close") }
            },
        )
    }
}