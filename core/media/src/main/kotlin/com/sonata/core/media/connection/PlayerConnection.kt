package com.sonata.core.media.connection

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.sonata.core.media.service.PlaybackService
import com.sonata.core.domain.model.Sound
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext

data class PlayerState(
    val current: Sound? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val queue: List<Sound> = emptyList(),
    val queueIndex: Int = -1,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffle: Boolean = false,
    val speed: Float = 1f,
)

/**
 * Single connection to the Media3 session; every screen observes [state].
 * Also drives the "Play it again" quick-loop and A-B loop.
 */
@Singleton
class PlayerConnection @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var controller: MediaController? = null

    private var abLoopStart: Long? = null
    private var abLoopEnd: Long? = null

    suspend fun connect() = withContext(Dispatchers.Main) {
        if (controller != null) return@withContext
        runCatching {
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val future = MediaController.Builder(context, token).buildAsync()
            controller = future.await()
            controller?.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    onPlayerChanged()
                }
            })
        }
        ticker()
    }

    private fun onPlayerChanged() {
        val player = controller ?: return
        val current = player.currentMediaItem
        val sound = current?.localConfiguration?.let { cfg ->
            queuedSounds.firstOrNull { it.filePath == cfg.uri.toString() }
        }
        _state.value = _state.value.copy(
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = player.duration.takeIf { it > 0 } ?: (sound?.durationMs ?: 0),
            queueIndex = player.currentMediaItemIndex,
            repeatMode = player.repeatMode,
            shuffle = player.shuffleModeEnabled,
            speed = player.playbackParameters.speed,
            current = sound,
            queue = queuedSounds,
        )
    }

    private var queuedSounds: List<Sound> = emptyList()

    private fun ticker() = scope.launch {
        while (isActive) {
            val player = controller ?: break
            val pos = player.currentPosition
            val (a, b) = abLoopStart to abLoopEnd
            if (a != null && b != null && pos >= b) {
                player.seekTo(a)
            }
            if (_state.value.positionMs != pos) {
                _state.value = _state.value.copy(positionMs = pos)
            }
            delay(200)
        }
    }

    fun play(sounds: List<Sound>, startIndex: Int = 0) {
        if (sounds.isEmpty()) return
        val player = controller ?: return
        queuedSounds = sounds
        player.setMediaItems(
            sounds.map { it.toMediaItem() },
            startIndex,
            0,
        )
        player.prepare()
        player.play()
    }

    fun playSingleLooped(sound: Sound) {
        val player = controller ?: return
        queuedSounds = listOf(sound)
        player.setMediaItem(sound.toMediaItem())
        player.repeatMode = Player.REPEAT_MODE_ONE // infinite, gapless via ExoPlayer loop
        player.prepare()
        player.play()
    }

    fun togglePlayPause() {
        val player = controller ?: return
        if (player.isPlaying) player.pause() else player.play()
    }

    fun next() { controller?.seekToNext() }
    fun previous() { controller?.seekToPrevious() }
    fun seekTo(positionMs: Long) { controller?.seekTo(positionMs) }
    fun setSpeed(speed: Float) {
        controller?.setPlaybackSpeed(speed)
    }

    /** Fades the mini player out, then pauses; [resumeAfterStop] restores it. */
    suspend fun fadeOutAndPause(durationMs: Long = 420L) {
        val player = controller ?: return
        if (!player.isPlaying) {
            player.pause()
            return
        }
        val startVolume = player.volume
        val steps = (durationMs / 20L).toInt().coerceAtLeast(4)
        for (i in 1..steps) {
            player.volume = startVolume * (1f - i.toFloat() / steps)
            delay(20L)
        }
        player.pause()
        player.volume = startVolume
    }

    fun resumeAfterStop() {
        controller?.play()
    }

    /**
     * Replaces the current item with the other playable variant while keeping
     * the musical position. The caller supplies the already mapped position.
     */
    fun replaceCurrent(sound: Sound, positionMs: Long): Boolean {
        val player = controller ?: return false
        val index = queuedSounds.indexOfFirst { it.id == sound.id }
        if (index < 0) return false
        val wasPlaying = player.isPlaying
        queuedSounds = queuedSounds.toMutableList().apply { this[index] = sound }
        player.replaceMediaItem(index, sound.toMediaItem())
        player.seekTo(positionMs.coerceAtLeast(0L))
        if (wasPlaying) player.play()
        onPlayerChanged()
        return true
    }

    fun cycleRepeat(): Int {
        val player = controller ?: return Player.REPEAT_MODE_OFF
        val next = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        player.repeatMode = next
        return next
    }

    fun addToQueue(sound: Sound) {
        val player = controller ?: return
        queuedSounds = queuedSounds + sound
        player.addMediaItem(sound.toMediaItem())
    }

    fun playNext(sound: Sound) {
        val player = controller ?: return
        val index = player.currentMediaItemIndex + 1
        queuedSounds = queuedSounds.toMutableList().apply { add(index.coerceAtMost(size), sound) }
        player.addMediaItem(index, sound.toMediaItem())
    }

    fun removeFromQueue(index: Int) {
        val player = controller ?: return
        queuedSounds = queuedSounds.toMutableList().apply { removeAt(index) }
        player.removeMediaItem(index)
    }

    fun reorderQueue(from: Int, to: Int) {
        val player = controller ?: return
        queuedSounds = queuedSounds.toMutableList().apply {
            add(to, removeAt(from))
        }
        player.moveMediaItem(from, to)
    }

    fun setAbLoop(startMs: Long?, endMs: Long?) {
        abLoopStart = startMs
        abLoopEnd = endMs
    }

    private fun Sound.toMediaItem(): MediaItem = MediaItem.Builder()
        .setUri(filePath)
        .setMediaId(id.toString())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist ?: source.name.lowercase().replaceFirstChar { it.uppercase() })
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .build(),
        )
        // Never clip to metadata: a stale duration would cut the real ending
        // off the file. ExoPlayer's own repeat mode handles seamless looping.
        .build()
}
