package com.sonata

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
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Lyrics
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import com.sonata.core.designsystem.LocalRadius
import com.sonata.core.designsystem.LocalSpacing
import com.sonata.core.designsystem.components.formatDuration
import com.sonata.core.designsystem.components.metadataLine
import com.sonata.core.domain.model.Lyrics
import com.sonata.core.domain.model.Sound
import com.sonata.core.domain.repository.LyricsRepository
import com.sonata.core.domain.repository.SoundRepository
import com.sonata.core.media.connection.PlayerConnection
import dagger.hilt.android.lifecycle.HiltViewModel
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
) : ViewModel() {

    val favorites = soundRepository.observeAll()
        .map { sounds -> sounds.filter { it.isFavorite }.map { it.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val _lyrics = MutableStateFlow<Lyrics?>(null)
    val lyrics: StateFlow<Lyrics?> = _lyrics.asStateFlow()

    private val _lyricsLoading = MutableStateFlow(false)
    val lyricsLoading: StateFlow<Boolean> = _lyricsLoading.asStateFlow()

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
    val sound = state.current

    var showLyrics by rememberSaveable { mutableStateOf(false) }
    var sleepMinutes by remember { mutableIntStateOf(0) }
    var abStart by remember { mutableStateOf<Long?>(null) }
    var abEnd by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(sound?.id) {
        viewModel.loadLyrics(sound)
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
            )

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

            PlayerDock(
                state = state,
                sound = sound,
                player = player,
                onOpenEq = onOpenEq,
            )
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
                Text(
                    sourceName(sound).uppercase(),
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(16.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    letterSpacing = 1.sp,
                )
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
                        .filter { it in listOf("full song", "clip only", "youtube", "sped up", "slowed", "nightcore", "reverb", "bass boosted") }
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
            ActionPill(
                icon = Icons.Outlined.Lyrics,
                label = "Lyrics",
                active = false,
                onClick = onOpenLyrics,
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
    val accent = text == "full song" || text == "youtube"
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
    return when {
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
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
) {
    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    val bars = waveform.ifEmpty { List(64) { 0.10f } }
    val playedColor = MaterialTheme.colorScheme.primary
    val restColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val cursorColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
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
        drawLine(
            color = cursorColor,
            start = Offset(fraction * size.width, 0f),
            end = Offset(fraction * size.width, size.height),
            strokeWidth = 2.dp.toPx(),
        )
    }
}