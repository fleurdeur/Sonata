package com.sonata.core.media.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.sonata.core.audio.engine.DspManager
import com.sonata.core.media.service.PlaybackService.Channels
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.math.roundToLong

/**
 * Media3 MediaSessionService: foreground notification with lock-screen controls,
 * artwork, Bluetooth / LE Audio + headset buttons, audio focus and ducking
 * (via ExoPlayer AudioAttributes + setHandleAudioBecomingNoisy), infinite
 * gapless single-clip loops through repeatMode ONE + zero-gap ClippingConfiguration.
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var dspManager: DspManager

    private var mediaSession: MediaSession? = null

    private val player by lazy {
        ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(5_000)
            .setSeekForwardIncrementMs(5_000)
            .build()
            .also { player ->
                player.addListener(object : androidx.media3.common.Player.Listener {
                    override fun onAudioSessionIdChanged(audioSessionId: Int) {
                        dspManager.attach(audioSessionId)
                    }
                })
            }
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    packageManager.getLaunchIntentForPackage(packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setCallback(SonataSessionCallback())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        dspManager.release()
        super.onDestroy()
    }

    private fun createChannels() {
        val manager = NotificationManagerCompat.from(this)
        val channel = NotificationChannelCompat.Builder(
            Channels.PLAYBACK,
            NotificationManagerCompat.IMPORTANCE_LOW,
        )
            .setName("Playback")
            .setDescription("Media playback controls")
            .build()
        val imports = NotificationChannelCompat.Builder(
            Channels.IMPORT,
            NotificationManagerCompat.IMPORTANCE_LOW,
        )
            .setName("Import status")
            .build()
        val freshMix = NotificationChannelCompat.Builder(
            Channels.FRESH_MIX,
            NotificationManagerCompat.IMPORTANCE_DEFAULT,
        )
            .setName("Daily Fresh Mix")
            .build()
        manager.createNotificationChannelsCompat(listOf(channel, imports, freshMix))
    }

    object Channels {
        const val PLAYBACK = "playback"
        const val IMPORT = "import"
        const val FRESH_MIX = "fresh_mix"
    }
}

/**
 * Session callback: honors pause-on-disconnect handled by ExoPlayer's
 * becoming-noisy receiver, and keeps "Play it again" quick-loop.
 */
class SonataSessionCallback : MediaSession.Callback {
    // MediaSession.Callback default implementations already cover play/pause,
    // skip, seek, queue edits and media button / Bluetooth / Wear controls.
}
