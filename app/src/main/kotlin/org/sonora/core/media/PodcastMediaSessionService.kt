// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.media

import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.sonora.R
import org.sonora.core.database.EpisodeDao
import org.sonora.core.database.EpisodeRepository
import org.sonora.core.database.PodcastDao
import org.sonora.core.download.EpisodeDownloadManager

@UnstableApi
@AndroidEntryPoint
class PodcastMediaSessionService : MediaSessionService() {
    @Inject lateinit var episodeDao: EpisodeDao
    @Inject lateinit var podcastDao: PodcastDao
    @Inject lateinit var episodeRepository: EpisodeRepository
    @Inject lateinit var okHttpClient: OkHttpClient
    @Inject lateinit var downloadManager: EpisodeDownloadManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var player: ExoPlayer
    private lateinit var manager: PlaybackStateManager
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this, SonoraRenderersFactory(this))
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(DefaultDataSource.Factory(this, OkHttpDataSource.Factory(okHttpClient))),
            )
            // Speech content type + handleAudioFocus = true: pauses on focus loss, ducks for notifications.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true,
            )
            // Pauses on ACTION_AUDIO_BECOMING_NOISY (headphones unplugged, Bluetooth disconnected).
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(SEEK_BACK_MS)
            .setSeekForwardIncrementMs(SEEK_FORWARD_MS)
            .build()

        manager = PlaybackStateManager(
            engine = ExoPlayerEngine(player),
            episodeDao = episodeDao,
            podcastDao = podcastDao,
            episodeRepository = episodeRepository,
            fileExists = { File(it).isFile },
            scope = scope,
            onEpisodeCompleted = downloadManager::onEpisodeCompleted,
        )
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) scope.launch { manager.onPlaybackEnded() }
            }

            // The player pauses itself on focus loss / unplugged headphones; mirror that into our state.
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                val systemPause = reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS ||
                    reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY
                if (!playWhenReady && systemPause) scope.launch { manager.pause() }
            }
        })

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(NOTIFICATION_CHANNEL_ID)
                .setChannelName(R.string.playback_channel_name)
                .build()
                .apply { setSmallIcon(R.drawable.ic_notification) },
        )
        session = MediaSession.Builder(this, ManagedPlayer(player))
            .setCallback(SessionCallback())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.release()
        session = null
        player.release()
        scope.cancel()
        super.onDestroy()
    }

    /** Routes transport commands through [PlaybackStateManager] so progress and sync actions are recorded. */
    private inner class ManagedPlayer(player: Player) : ForwardingPlayer(player) {
        override fun play() {
            val id = manager.state.value.episodeId ?: return super.play()
            scope.launch { manager.play(id) }
        }

        override fun pause() {
            scope.launch { manager.pause() }
        }

        override fun setPlayWhenReady(playWhenReady: Boolean) = if (playWhenReady) play() else pause()

        override fun stop() {
            scope.launch { manager.stop() }
        }

        override fun seekTo(positionMs: Long) {
            scope.launch { manager.seekTo(positionMs) }
        }

        override fun seekTo(mediaItemIndex: Int, positionMs: Long) = seekTo(positionMs)
        override fun seekForward() = seekTo(currentPosition + seekForwardIncrement)
        override fun seekBack() = seekTo((currentPosition - seekBackIncrement).coerceAtLeast(0))
    }

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val commands = SessionCommands.Builder()
                .addSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.commands)
                .apply { PlaybackCommands.ALL.forEach(::add) }
                .build()
            return MediaSession.ConnectionResult.accept(commands, MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            scope.launch {
                when (customCommand) {
                    PlaybackCommands.PLAY_EPISODE -> manager.play(args.getLong(PlaybackCommands.EXTRA_EPISODE_ID))
                    PlaybackCommands.SET_SPEED -> manager.setSpeed(args.getFloat(PlaybackCommands.EXTRA_SPEED, 1f))
                    PlaybackCommands.SET_SKIP_SILENCE ->
                        manager.setSkipSilence(args.getBoolean(PlaybackCommands.EXTRA_ENABLED))
                    PlaybackCommands.START_SLEEP_TIMER ->
                        manager.startSleepTimer(args.getLong(PlaybackCommands.EXTRA_DURATION_MS))
                    PlaybackCommands.CANCEL_SLEEP_TIMER -> manager.cancelSleepTimer()
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    private companion object {
        const val NOTIFICATION_CHANNEL_ID = "playback"
        const val SEEK_BACK_MS = 10_000L
        const val SEEK_FORWARD_MS = 30_000L
    }
}
