package com.sonata

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Lyrics
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import com.sonata.core.designsystem.LocalRadius
import com.sonata.core.designsystem.LocalSpacing
import com.sonata.core.designsystem.components.formatDuration
import com.sonata.core.designsystem.components.metadataLine
import com.sonata.core.domain.model.Cue
import com.sonata.core.domain.model.CueKind
import com.sonata.core.domain.model.Lyrics
import com.sonata.core.domain.model.PlaybackVariant
import com.sonata.core.domain.model.Sound
import com.sonata.core.domain.repository.CueRepository
import com.sonata.core.domain.repository.LyricsRepository
import com.sonata.core.domain.repository.SoundRepository
import com.sonata.core.media.connection.PlayerConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class NowPlayingViewModel @Inject constructor(
    private val soundRepository: SoundRepository,
    private val lyricsRepository: LyricsRepository,
    private val cueRepository: CueRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val favorites = soundRepository.observeAll()
        .map { sounds -> sounds.filter { it.isFavorite }.map { it.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val _lyrics = MutableStateFlow<Lyrics?>(null)
    val lyrics: StateFlow<Lyrics?> = _lyrics.asStateFlow()

    private val _lyricsLoading = MutableStateFlow(false)
    val lyricsLoading: StateFlow<Boolean> = _lyricsLoading.asStateFlow()

    private val _cues = MutableStateFlow<List<Cue>>(emptyList())
    val cues: StateFlow<List<Cue>> = _cues.asStateFlow()
    private var cueJob: Job? = null

    private var loadedSoundId: Long? = null
    private var lyricsJob: Job? = null
    private var lyricsRequest = 0

    fun toggleFavorite(id: Long, favorite: Boolean) = viewModelScope.launch {
        soundRepository.setFavorite(id, !favorite)
    }

    fun loadLyrics(sound: Sound?) {
        if (sound == null) {
            loadedSoundId = null
            lyricsJob?.cancel()
            lyricsRequest++
            _lyrics.value = null
            _lyricsLoading.value = false
            return
        }
        if (loadedSoundId == sound.id) return
        loadedSoundId = sound.id
        _lyrics.value = null
        if (sound.tags.contains("clip only")) {
            // The clip is an excerpt; a full-song lyric sheet would be out of
            // sync, so don't pretend it fits. Cancel any previous track's job
            // so its result cannot land on this one.
            lyricsJob?.cancel()
            lyricsRequest++
            _lyricsLoading.value = false
            return
        }
        _lyricsLoading.value = true
        lyricsJob?.cancel()
        val request = ++lyricsRequest
        lyricsJob = viewModelScope.launch {
            val result = fetch(sound, refresh = false)
            if (request != lyricsRequest) return@launch
            _lyrics.value = result
            _lyricsLoading.value = false
        }
    }

    fun refreshLyrics(sound: Sound?) {
        if (sound == null || sound.tags.contains("clip only")) return
        _lyricsLoading.value = true
        lyricsJob?.cancel()
        val request = ++lyricsRequest
        lyricsJob = viewModelScope.launch {
            val result = fetch(sound, refresh = true)
            if (request != lyricsRequest) return@launch
            _lyrics.value = result
            _lyricsLoading.value = false
        }
    }

    fun setLyricsOffset(soundId: Long, offsetMs: Long) = viewModelScope.launch {
        lyricsRepository.setOffset(soundId, offsetMs)
        _lyrics.value = _lyrics.value?.copy(offsetMs = offsetMs.coerceIn(-2_000L, 2_000L))
    }

    fun switchVariant(sound: Sound, target: PlaybackVariant, onReady: (Sound) -> Unit) {
        if (sound.activeVariant == target) return
        viewModelScope.launch {
            val updated = soundRepository.setVariant(sound.id, target) ?: return@launch
            onReady(updated)
        }
    }

    fun setCover(soundId: Long, uri: Uri, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = runCatching {
                val dir = File(context.filesDir, "covers").apply { mkdirs() }
                val target = File(dir, "sound_${soundId}_${System.currentTimeMillis()}.jpg")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } ?: error("The picked image could not be opened")
                if (target.length() > 12L * 1024L * 1024L) {
                    target.delete()
                    error("Cover images must be smaller than 12 MB")
                }
                soundRepository.updateCover(soundId, target.absolutePath)
                // A fresh filename also bypasses Coil's path-based cache.
                dir.listFiles()?.forEach { old ->
                    if (old != target && old.name.startsWith("sound_${soundId}_")) old.delete()
                }
            }.isSuccess
            onDone(ok)
        }
    }

    fun loadCues(soundId: Long?) {
        cueJob?.cancel()
        if (soundId == null) {
            _cues.value = emptyList()
            return
        }
        cueJob = viewModelScope.launch {
            cueRepository.observe(soundId).collect { _cues.value = it }
        }
    }

    fun addCue(
        soundId: Long,
        positionMs: Long,
        kind: CueKind,
        durationMs: Long,
        timelineOffsetMs: Long,
    ) {
        viewModelScope.launch {
            // Cues always live on the full-song timeline, even when the user
            // drops them while listening to the clip.
            val start = (positionMs + timelineOffsetMs).coerceAtLeast(0L)
            val endLimit = timelineOffsetMs + durationMs.coerceAtLeast(positionMs + 500L)
            val loopEnd = if (kind == CueKind.LOOP) {
                (start + 8_000L).coerceAtMost(endLimit.coerceAtLeast(start + 500L))
            } else {
                null
            }
            cueRepository.add(
                soundId = soundId,
                positionMs = start,
                label = kind.label + " " + formatDuration(positionMs),
                kind = kind,
                endMs = loopEnd,
            )
        }
    }

    fun deleteCue(id: Long) {
        viewModelScope.launch { cueRepository.delete(id) }
    }

    private suspend fun fetch(sound: Sound, refresh: Boolean): Lyrics? = try {
        if (refresh) {
            lyricsRepository.refresh(
                soundId = sound.id,
                title = sound.title,
                artist = sound.artist,
                durationMs = sound.durationMs,
                speed = speedFromTags(sound),
            )
        } else {
            lyricsRepository.getOrFetch(
                soundId = sound.id,
                title = sound.title,
                artist = sound.artist,
                durationMs = sound.durationMs,
                speed = speedFromTags(sound),
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun speedFromTags(sound: Sound): Float = sound.tags
        .firstOrNull { Regex("""^\d+(\.\d+)?x$""").matches(it) }
        ?.dropLast(1)
        ?.toFloatOrNull()
        ?: 1f
}

/**
 * Full player. Big artwork, a tinted backdrop, one fixed control dock and a
 * separate lyrics page that behaves like Apple Music: active line grows,
 * neighbours fade and the page follows the song.
 */
@Composable
fun NowPlayingScreen(
    player: PlayerConnection,
    onCollapse: () -> Unit,
    onOpenEq: () -> Unit,
    viewModel: NowPlayingViewModel = hiltViewModel(),
) {
    val state by player.state.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val lyrics by viewModel.lyrics.collectAsState()
    val lyricsLoading by viewModel.lyricsLoading.collectAsState()
    val cues by viewModel.cues.collectAsState()
    val sound = state.current
    val context = LocalContext.current

    var showLyrics by rememberSaveable { mutableStateOf(false) }
    var sleepMinutes by remember { mutableIntStateOf(0) }
    var abStart by remember { mutableStateOf<Long?>(null) }
    var abEnd by remember { mutableStateOf<Long?>(null) }
    val coverPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        val id = sound?.id ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        viewModel.setCover(id, uri) { ok ->
            Toast.makeText(
                context,
                if (ok) "Cover updated" else "Could not use that image",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    LaunchedEffect(sound?.id) {
        viewModel.loadLyrics(sound)
        viewModel.loadCues(sound?.id)
        showLyrics = false
        // A loop belongs to one recording; never carry it to the next track.
        abStart = null
        abEnd = null
        player.setAbLoop(null, null)
    }

    LaunchedEffect(sleepMinutes) {
        if (sleepMinutes > 0) {
            delay(sleepMinutes * 60_000L)
            if (player.state.value.isPlaying) player.togglePlayPause()
            sleepMinutes = 0
        }
    }

    val background = MaterialTheme.colorScheme.background
    val gradient = Brush.verticalGradient(
        0f to MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
        0.42f to MaterialTheme.colorScheme.background,
        1f to MaterialTheme.colorScheme.background,
    )

    Box(Modifier.fillMaxSize().background(gradient)) {
        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
            NowPlayingTopBar(
                sound = sound,
                favorite = sound != null && sound.id in favorites,
                lyricsActive = showLyrics,
                onCollapse = onCollapse,
                onToggleLyrics = { showLyrics = !showLyrics },
                onToggleFavorite = {
                    sound?.let { viewModel.toggleFavorite(it.id, it.id in favorites) }
                },
                onExport = { sound?.let { exportSound(context, it) } },
            )

            if (sound != null && sound.hasVariants) {
                VariantSwitcher(
                    sound = sound,
                    onSelect = { target ->
                        val offset = if (target == PlaybackVariant.FULL) {
                            state.positionMs + sound.matchOffsetMs
                        } else {
                            state.positionMs - sound.matchOffsetMs
                        }.coerceAtLeast(0L)
                        viewModel.switchVariant(sound, target) { updated ->
                            player.replaceCurrent(updated, offset)
                        }
                    },
                    onJumpToMoment = { player.seekTo(sound.matchOffsetMs) },
                )
            }

            Crossfade(
                targetState = showLyrics,
                animationSpec = tween(280),
                label = "nowPlayingPage",
                modifier = Modifier.weight(1f),
            ) { lyricsPage ->
                if (lyricsPage) {
                    LyricsPane(
                        lyrics = lyrics,
                        loading = lyricsLoading,
                        positionMs = state.positionMs,
                        onSeek = { player.seekTo(it) },
                        onRefresh = { viewModel.refreshLyrics(sound) },
                        modifier = Modifier.fillMaxSize(),
                        clipOnly = sound?.tags?.contains("clip only") == true,
                        onOffsetChange = { offset ->
                            sound?.let { viewModel.setLyricsOffset(it.id, offset) }
                        },
                    )
                } else {
                    PlayerPage(
                        sound = sound,
                        state = state,
                        lyrics = lyrics,
                        lyricsLoading = lyricsLoading,
                        abStart = abStart,
                        abEnd = abEnd,
                        sleepMinutes = sleepMinutes,
                        onOpenLyrics = { showLyrics = true },
                        onToggleSleep = {
                            sleepMinutes = when (sleepMinutes) {
                                0 -> 15
                                15 -> 30
                                30 -> 60
                                else -> 0
                            }
                        },
                        onCycleSpeed = {
                            val speeds = listOf(1f, 1.25f, 1.5f, 0.75f)
                            val current = speeds.indexOfFirst { abs(it - state.speed) < 0.01f }
                            player.setSpeed(speeds[((current.coerceAtLeast(0)) + 1) % speeds.size])
                        },
                        onChangeCover = { coverPicker.launch("image/*") },
                        onCycleAb = {
                            when {
                                abStart == null -> abStart = state.positionMs
                                abEnd == null -> {
                                    abEnd = state.positionMs
                                    player.setAbLoop(abStart, state.positionMs)
                                }
                                else -> {
                                    abStart = null
                                    abEnd = null
                                    player.setAbLoop(null, null)
                                }
                            }
                        },
                    )
                }
            }

            if (!showLyrics && sound != null) {
                val clipOffset = if (sound.activeVariant == PlaybackVariant.CLIP) {
                    sound.matchOffsetMs
                } else {
                    0L
                }
                val visibleCues = if (clipOffset <= 0L) {
                    cues
                } else {
                    cues
                        .filter { it.positionMs >= clipOffset }
                        .map { cue ->
                            cue.copy(
                                positionMs = cue.positionMs - clipOffset,
                                endMs = cue.endMs?.minus(clipOffset)?.coerceAtLeast(0L),
                            )
                        }
                }
                CueBar(
                    cues = visibleCues,
                    durationMs = state.durationMs.takeIf { it > 0 } ?: sound.durationMs,
                    onAddCue = {
                        viewModel.addCue(
                            soundId = sound.id,
                            positionMs = state.positionMs,
                            kind = CueKind.HOT,
                            durationMs = state.durationMs,
                            timelineOffsetMs = clipOffset,
                        )
                    },
                    onAddLoop = {
                        viewModel.addCue(
                            soundId = sound.id,
                            positionMs = state.positionMs,
                            kind = CueKind.LOOP,
                            durationMs = state.durationMs,
                            timelineOffsetMs = clipOffset,
                        )
                    },
                    onCue = { cue ->
                        if (cue.kind == CueKind.LOOP) {
                            val end = (cue.endMs ?: (cue.positionMs + 8_000L)).coerceAtMost(
                                (state.durationMs.takeIf { it > 0 } ?: sound.durationMs)
                                    .coerceAtLeast(cue.positionMs + 500L),
                            )
                            player.setAbLoop(cue.positionMs, end)
                        } else {
                            player.seekTo(cue.positionMs)
                        }
                    },
                    onDeleteCue = viewModel::deleteCue,
                )
            }

            PlayerDock(
                state = state,
                sound = sound,
                player = player,
                onOpenEq = onOpenEq,
            )
        }
    }
}

private fun exportSound(context: Context, sound: Sound) {
    val file = File(sound.filePath)
    if (!file.exists()) {
        Toast.makeText(context, "The audio file is missing", Toast.LENGTH_SHORT).show()
        return
    }
    runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file,
        )
        val mime = if (file.extension.equals("wav", ignoreCase = true)) "audio/wav" else "audio/mp4"
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Export " + sound.title)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }.onFailure {
        Toast.makeText(context, "Could not export this sound", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun VariantSwitcher(
    sound: Sound,
    onSelect: (PlaybackVariant) -> Unit,
    onJumpToMoment: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.lgDp, vertical = 4.dp),
    ) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val options = listOf(
                PlaybackVariant.CLIP to "TikTok clip",
                PlaybackVariant.FULL to "Full song",
            )
            options.forEachIndexed { index, (variant, label) ->
                SegmentedButton(
                    selected = sound.activeVariant == variant,
                    onClick = { onSelect(variant) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                ) {
                    Text(
                        if (variant == PlaybackVariant.FULL && sound.effectSpeed != 1f) {
                            "Full song · " + sound.effectSpeed + "x"
                        } else {
                            label
                        },
                    )
                }
            }
        }
        if (sound.activeVariant == PlaybackVariant.FULL && sound.matchOffsetMs > 0) {
            TextButton(onClick = onJumpToMoment) {
                Text("Jump to the TikTok moment · " + formatDuration(sound.matchOffsetMs))
            }
        }
    }
}

@Composable
private fun NowPlayingTopBar(
    sound: Sound?,
    favorite: Boolean,
    lyricsActive: Boolean,
    onCollapse: () -> Unit,
    onToggleLyrics: () -> Unit,
    onToggleFavorite: () -> Unit,
    onExport: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCollapse) {
            Icon(
                Icons.Filled.ExpandMore,
                contentDescription = "Collapse player",
                modifier = Modifier.size(28.dp),
            )
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "NOW PLAYING",
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.6.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                sourceName(sound),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onToggleLyrics) {
            Icon(
                Icons.Outlined.Lyrics,
                contentDescription = if (lyricsActive) "Show player" else "Show lyrics",
                tint = if (lyricsActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onExport, enabled = sound != null) {
            Icon(
                Icons.Filled.Share,
                contentDescription = "Export current audio",
            )
        }
        IconButton(onClick = onToggleFavorite, enabled = sound != null) {
            Icon(
                if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = if (favorite) "Remove from favorites" else "Add to favorites",
                tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PlayerPage(
    sound: Sound?,
    state: com.sonata.core.media.connection.PlayerState,
    lyrics: Lyrics?,
    lyricsLoading: Boolean,
    abStart: Long?,
    abEnd: Long?,
    sleepMinutes: Int,
    onOpenLyrics: () -> Unit,
    onToggleSleep: () -> Unit,
    onCycleSpeed: () -> Unit,
    onCycleAb: () -> Unit,
    onChangeCover: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val radius = LocalRadius.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth(0.80f)
                .aspectRatio(1f)
                .shadow(22.dp, RoundedCornerShape(30.dp))
                .clip(RoundedCornerShape(30.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            val cover = sound?.coverPath
            if (cover != null && File(cover).exists()) {
                AsyncImage(
                    model = File(cover),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    sound?.title?.trim()?.take(2)?.uppercase() ?: "♪",
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.62f to androidx.compose.ui.graphics.Color.Transparent,
                            1f to androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.42f),
                        ),
                    ),
            )
            if (sound != null) {
                IconButton(
                    onClick = onChangeCover,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(10.dp)
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                            CircleShape,
                        ),
                ) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = "Change cover art",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        Spacer(Modifier.height(22.dp))

        Column(Modifier.fillMaxWidth()) {
            Text(
                sound?.title ?: "Nothing playing",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                sound?.let(::metadataLine) ?: "Pick a sound from your library",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (sound != null) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    sound.tags
                        .filter {
                            it in listOf(
                                "verified", "full song", "clip only", "youtube",
                                "sped up", "slowed", "nightcore", "reverb", "bass boosted",
                            ) || it.startsWith("match ")
                        }
                        .take(3)
                        .forEach { tag -> TrackBadge(tag) }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        LyricsTeaser(
            lyrics = lyrics,
            loading = lyricsLoading,
            positionMs = state.positionMs,
            clipOnly = sound?.tags?.contains("clip only") == true,
            onClick = onOpenLyrics,
        )

        Spacer(Modifier.height(14.dp))

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActionPill(
                icon = Icons.Outlined.Timer,
                label = if (sleepMinutes == 0) "Sleep" else sleepMinutes.toString() + " min",
                active = sleepMinutes > 0,
                onClick = onToggleSleep,
            )
            ActionPill(
                icon = null,
                label = speedLabel(state.speed),
                active = abs(state.speed - 1f) > 0.01f,
                onClick = onCycleSpeed,
            )
            ActionPill(
                icon = null,
                label = when {
                    abStart == null -> "A–B"
                    abEnd == null -> "Set B"
                    else -> "Clear A–B"
                },
                active = abStart != null,
                onClick = onCycleAb,
            )
        }

        if (abStart != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                if (abEnd == null) {
                    "A set at " + formatDuration(abStart ?: 0L) + " · play to the end of the phrase and tap Set B"
                } else {
                    "Looping " + formatDuration(abStart ?: 0L) + " – " + formatDuration(abEnd ?: 0L)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(spacing.xlDp))
    }
}

@Composable
private fun PlayerDock(
    state: com.sonata.core.media.connection.PlayerState,
    sound: Sound?,
    player: PlayerConnection,
    onOpenEq: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to MaterialTheme.colorScheme.background.copy(alpha = 0f),
                    0.22f to MaterialTheme.colorScheme.background,
                ),
            )
            .padding(horizontal = 20.dp)
            .padding(top = 8.dp, bottom = 8.dp),
    ) {
        WaveformScrubber(
            waveform = sound?.waveform?.toList().orEmpty(),
            bpm = sound?.bpm,
            positionMs = state.positionMs,
            durationMs = state.durationMs.takeIf { it > 0 } ?: sound?.durationMs ?: 0,
            onSeek = { player.seekTo(it) },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                formatDuration(state.positionMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                formatDuration(state.durationMs.takeIf { it > 0 } ?: sound?.durationMs ?: 0),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(6.dp))

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = { player.cycleRepeat() }) {
                Icon(
                    if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                    contentDescription = "Repeat mode",
                    tint = if (state.repeatMode != Player.REPEAT_MODE_OFF) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            IconButton(onClick = { player.previous() }) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(38.dp))
            }
            FilledIconButton(
                onClick = { player.togglePlayPause() },
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
            ) {
                Icon(
                    if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(34.dp),
                )
            }
            IconButton(onClick = { player.next() }) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Next", modifier = Modifier.size(38.dp))
            }
            IconButton(onClick = onOpenEq) {
                Icon(Icons.Outlined.GraphicEq, contentDescription = "Equalizer")
            }
        }
    }
}

@Composable
private fun LyricsTeaser(
    lyrics: Lyrics?,
    loading: Boolean,
    positionMs: Long,
    clipOnly: Boolean,
    onClick: () -> Unit,
) {
    val preview = when {
        clipOnly -> "Lyrics need the full song · see why"
        loading -> "Finding the right lyrics…"
        lyrics == null || lyrics.isEmpty -> "Lyrics not found · tap to search again"
        lyrics.synced -> {
            val index = lyrics.indexAt(positionMs)
            lyrics.lines.getOrNull(index.coerceAtLeast(0))?.text ?: lyrics.lines.first().text
        }
        else -> lyrics.plainText.lineSequence().firstOrNull().orEmpty()
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Lyrics,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                preview,
                Modifier.weight(1f).padding(start = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "Open",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun TrackBadge(text: String) {
    val accent = text == "verified" || text == "full song" || text == "youtube"
    Text(
        text,
        Modifier
            .clip(CircleShape)
            .background(
                if (accent) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelSmall,
        color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CueBar(
    cues: List<Cue>,
    durationMs: Long,
    onAddCue: () -> Unit,
    onAddLoop: () -> Unit,
    onCue: (Cue) -> Unit,
    onDeleteCue: (Long) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionPill(
            icon = Icons.Filled.Add,
            label = "Cue",
            active = false,
            onClick = onAddCue,
        )
        ActionPill(
            icon = Icons.Filled.Repeat,
            label = "Loop",
            active = false,
            onClick = onAddLoop,
        )
        cues.forEach { cue ->
            InputChip(
                selected = false,
                onClick = { onCue(cue) },
                label = {
                    Text(
                        cue.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
                trailingIcon = {
                    IconButton(
                        onClick = { onDeleteCue(cue.id) },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Delete " + cue.label,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun ActionPill(
    icon: ImageVector?,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (active) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.66f)
        },
        modifier = Modifier
            .heightIn(min = 48.dp)
            .semantics { if (active) selected = true },
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private fun sourceName(sound: Sound?): String {
    if (sound == null) return "SONATA"
    val match = sound.tags.firstOrNull { it.startsWith("match ") }
    val suffix = if (match != null) " · " + match.removePrefix("match ") + " match" else ""
    return when {
        sound.tags.contains("verified") && sound.tags.contains("youtube") ->
            "YouTube · verified full song" + suffix
        sound.tags.contains("verified") -> "Verified full song" + suffix
        sound.tags.contains("youtube") -> "YouTube · full song"
        sound.tags.contains("full song") -> "Full song"
        sound.tags.contains("clip only") -> "Original clip"
        else -> sound.source.name.lowercase().replaceFirstChar { it.uppercase() }
    }
}

private fun speedLabel(speed: Float): String {
    val value = (speed * 100f).roundToInt() / 100f
    return value.toString().removeSuffix(".0") + "×"
}

/** Waveform with tap-to-seek and drag-to-scrub, drawn from the stored peaks. */
@Composable
private fun WaveformScrubber(
    waveform: List<Float>,
    bpm: Int?,
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
) {
    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    val bars = waveform.ifEmpty { List(64) { 0.10f } }
    val playedColor = MaterialTheme.colorScheme.primary
    val restColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val cursorColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    val beatColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val fraction = scrubFraction ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    fun previewAt(x: Float, width: Int): Float? {
        if (durationMs <= 0 || width <= 0) return null
        val f = (x / width).coerceIn(0f, 1f)
        scrubFraction = f
        return f
    }

    fun seekAt(x: Float, width: Int) {
        previewAt(x, width)?.let { onSeek((it * durationMs).toLong()) }
    }

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .pointerInput(durationMs) {
                detectTapGestures { offset -> seekAt(offset.x, size.width) }
            }
            .pointerInput(durationMs) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        // Commit once: seeking on every pixel makes playback stutter.
                        scrubFraction?.let { f -> onSeek((f * durationMs).toLong()) }
                        scrubFraction = null
                    },
                    onDragCancel = { scrubFraction = null },
                ) { change, _ ->
                    change.consume()
                    previewAt(change.position.x, size.width)
                }
            }
            .semantics {
                contentDescription = "Seek"
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                setProgress { target ->
                    if (durationMs > 0) onSeek((target.coerceIn(0f, 1f) * durationMs).toLong())
                    true
                }
            },
    ) {
        val count = bars.size
        val slot = size.width / count
        val barWidth = slot * 0.62f
        bars.forEachIndexed { i, peak ->
            val played = i.toFloat() / count <= fraction
            val h = (peak.coerceIn(0.02f, 1f) * size.height).coerceAtLeast(2f)
            drawRoundRect(
                color = if (played) playedColor else restColor,
                topLeft = Offset(i * slot, (size.height - h) / 2f),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
        // Downbeat grid: four beats per barlines keep the ticks readable
        // without turning a three-minute waveform into a solid block.
        bpm?.takeIf { it in 40..220 && durationMs > 0 }?.let { tempo ->
            val barMs = 60_000.0 / tempo * 4.0
            var t = 0.0
            while (t < durationMs) {
                val x = (t / durationMs * size.width).toFloat()
                drawLine(
                    color = beatColor,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
                t += barMs
            }
        }
        drawLine(
            color = cursorColor,
            start = Offset(fraction * size.width, 0f),
            end = Offset(fraction * size.width, size.height),
            strokeWidth = 2.dp.toPx(),
        )
    }
}