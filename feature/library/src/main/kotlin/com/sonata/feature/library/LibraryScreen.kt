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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import com.sonata.core.domain.repository.LyricsRepository
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

enum class LibraryFilter(val label: String) {
    ALL("All"),
    FULL_SONGS("Full songs"),
    CLIPS("Clips"),
    WITH_LYRICS("With lyrics"),
    WITH_BPM("Analysed"),
    UNDER_MINUTE("Under 1 min"),
    OVER_MINUTE("Over 1 min"),
}

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val soundRepository: SoundRepository,
    private val playlistRepository: PlaylistRepository,
    private val lyricsRepository: LyricsRepository,
    private val player: PlayerConnection,
) : ViewModel() {

    val sounds: StateFlow<List<Sound>> = soundRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val playlists: StateFlow<List<Playlist>> = playlistRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val soundsWithLyrics: StateFlow<Set<Long>> = lyricsRepository.observeSoundIdsWithLyrics()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

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

    fun updateTags(sound: Sound, title: String, artist: String?, tags: List<String>) = viewModelScope.launch {
        val newTitle = title.trim().take(120).ifBlank { sound.title }
        val newArtist = artist?.trim()?.take(120)?.takeIf { it.isNotBlank() }
        val changedIdentity = newTitle != sound.title || newArtist != sound.artist
        soundRepository.upsert(
            sound.copy(
                title = newTitle,
                artist = newArtist,
                tags = tags.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(12),
            ),
        )
        if (changedIdentity) {
            val speed = sound.tags.firstOrNull { Regex("""^\d+(\.\d+)?x$""").matches(it) }
                ?.dropLast(1)
                ?.toFloatOrNull()
                ?: 1f
            runCatching {
                lyricsRepository.refresh(
                    soundId = sound.id,
                    title = newTitle,
                    artist = newArtist,
                    durationMs = sound.durationMs,
                    speed = speed,
                )
            }
        }
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
    val lyricsIds by viewModel.soundsWithLyrics.collectAsState()
    val spacing = LocalSpacing.current
    val radius = LocalRadius.current

    var tab by remember { mutableStateOf(0) }
    var sort by remember { mutableStateOf(LibrarySort.RECENT) }
    var filter by remember { mutableStateOf(LibraryFilter.ALL) }
    var sortMenu by remember { mutableStateOf(false) }
    var editingSound by remember { mutableStateOf<Sound?>(null) }
    var selection by remember { mutableStateOf(setOf<Long>()) }
    var confirmDelete by remember { mutableStateOf<Set<Long>?>(null) }
    var showNewPlaylist by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var playlistPickerFor by remember { mutableStateOf<List<Long>?>(null) }

    val sorted = remember(sounds, sort, filter, lyricsIds) {
        val filtered = when (filter) {
            LibraryFilter.ALL -> sounds
            LibraryFilter.FULL_SONGS -> sounds.filter { "full song" in it.tags }
            LibraryFilter.CLIPS -> sounds.filter { "clip only" in it.tags }
            LibraryFilter.WITH_LYRICS -> sounds.filter { it.id in lyricsIds }
            LibraryFilter.WITH_BPM -> sounds.filter { it.bpm != null }
            LibraryFilter.UNDER_MINUTE -> sounds.filter { it.durationMs in 1..59_999L }
            LibraryFilter.OVER_MINUTE -> sounds.filter { it.durationMs >= 60_000L }
        }
        when (sort) {
            LibrarySort.RECENT -> filtered.sortedByDescending { it.addedAt }
            LibrarySort.TITLE -> filtered.sortedBy { it.title.lowercase() }
            LibrarySort.PLAYED -> filtered.sortedByDescending { it.playCount }
            LibrarySort.BPM -> filtered.sortedBy { it.bpm ?: 0 }
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
                    modifier = Modifier.weight(1f),
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

        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp),
        ) {
            Row(Modifier.padding(4.dp)) {
                listOf("Sounds", "Playlists").forEachIndexed { index, label ->
                    val selected = tab == index
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(CircleShape)
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary
                                else androidx.compose.ui.graphics.Color.Transparent,
                            )
                            .clickable { tab = index }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }

        if (tab == 0) {
            LazyRow(
                Modifier.fillMaxWidth().padding(bottom = spacing.xsDp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(LibraryFilter.entries, key = { it.name }) { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { filter = option },
                        label = { Text(option.label) },
                    )
                }
            }
        }

        val selectedSounds = sorted.filter { it.id in selection }
        val allFavorite = selectedSounds.isNotEmpty() && selectedSounds.all { it.isFavorite }

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
                    viewModel.play(selectedSounds)
                }) { Icon(Icons.Filled.PlayArrow, contentDescription = "Play selected") }
                IconButton(onClick = {
                    viewModel.favoriteAll(selectedSounds, favorite = !allFavorite)
                }) {
                    Icon(
                        if (allFavorite) Icons.Filled.FavoriteBorder else Icons.Filled.Favorite,
                        contentDescription = if (allFavorite) "Remove selected from favorites" else "Favorite selected",
                    )
                }
                if (selectedSounds.size == 1) {
                    IconButton(onClick = { editingSound = selectedSounds.first() }) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit tags")
                    }
                }
                IconButton(onClick = { playlistPickerFor = selection.toList() }) {
                    Icon(Icons.Outlined.PlaylistAdd, contentDescription = "Add selected to playlist")
                }
                IconButton(onClick = {
                    confirmDelete = selection
                }) { Icon(Icons.Filled.Delete, contentDescription = "Delete selected") }
                TextButton(onClick = { selection = emptySet() }) { Text("Done") }
            }
        }

        when (tab) {
            0 -> if (sorted.isEmpty()) {
                EmptyState(
                    title = if (filter == LibraryFilter.ALL) "No sounds yet" else "Nothing matches this filter",
                    message = if (filter == LibraryFilter.ALL) {
                        "Import a file or share a TikTok video to Sonata."
                    } else {
                        "Try another filter — your library is still intact."
                    },
                    primaryLabel = if (filter == LibraryFilter.ALL) null else "Show all",
                    onPrimary = if (filter == LibraryFilter.ALL) null else ({ filter = LibraryFilter.ALL }),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
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
                modifier = Modifier.weight(1f),
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

    val deleting = confirmDelete
    if (deleting != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(if (deleting.size == 1) "Delete this sound?" else "Delete " + deleting.size + " sounds?") },
            text = { Text("The audio files will be removed from this device. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(deleting.toList())
                        selection = emptySet()
                        confirmDelete = null
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
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

    val editing = editingSound
    if (editing != null) {
        var title by remember(editing.id) { mutableStateOf(editing.title) }
        var artist by remember(editing.id) { mutableStateOf(editing.artist.orEmpty()) }
        var tagText by remember(editing.id) { mutableStateOf(editing.tags.joinToString(", ")) }
        AlertDialog(
            onDismissRequest = { editingSound = null },
            title = { Text("Edit tags") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        singleLine = true,
                        label = { Text("Title") },
                    )
                    OutlinedTextField(
                        value = artist,
                        onValueChange = { artist = it },
                        singleLine = true,
                        label = { Text("Artist") },
                    )
                    OutlinedTextField(
                        value = tagText,
                        onValueChange = { tagText = it },
                        label = { Text("Tags (comma separated)") },
                        supportingText = { Text("e.g. full song, live, gym") },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.updateTags(
                            sound = editing,
                            title = title,
                            artist = artist,
                            tags = tagText.split(',').map { it.trim() }.filter { it.isNotBlank() },
                        )
                        editingSound = null
                        selection = emptySet()
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingSound = null }) { Text("Cancel") } },
        )
    }
}