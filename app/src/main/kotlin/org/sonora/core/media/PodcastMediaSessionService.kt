// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.media

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineExceptionHandler
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
import org.sonora.core.network.withTransportPolicy
import org.sonora.feature.settings.AppSettings

@UnstableApi
@AndroidEntryPoint
class PodcastMediaSessionService : MediaSessionService() {
    @Inject lateinit var episodeDao: EpisodeDao
    @Inject lateinit var podcastDao: PodcastDao
    @Inject lateinit var episodeRepository: EpisodeRepository
    @Inject lateinit var okHttpClient: OkHttpClient
    @Inject lateinit var downloadManager: EpisodeDownloadManager
    @Inject lateinit var settings: AppSettings
    @Inject lateinit var playQueue: PlayQueue
    @Inject lateinit var hub: PlaybackHub

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, e -> Log.w(TAG, "Playback command failed", e) },
    )
    private lateinit var player: ExoPlayer
    private lateinit var manager: PlaybackStateManager
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val allowCleartext = settings.allowCleartext
        player = ExoPlayer.Builder(this, SonoraRenderersFactory(this))
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    DefaultDataSource.Factory(
                        this,
                        OkHttpDataSource.Factory(okHttpClient.withTransportPolicy(allowCleartext)),
                    ),
                ),
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
            allowCleartext = allowCleartext,
            queue = playQueue,
            onEpisodeCompleted = downloadManager::onEpisodeCompleted,
        )
        hub.attach(manager)
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) scope.launch { manager.onPlaybackEnded() }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "Playback error", error)
                scope.launch { manager.onPlayerError(failureOf(error)) }
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
        hub.detach(manager)
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
            // Custom commands (play any episode id, ...) are for our own UI and system controllers only.
            val privileged = controller.isTrusted || controller.packageName == packageName
            val commands = SessionCommands.Builder()
                .addSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.commands)
                .apply { if (privileged) PlaybackCommands.ALL.forEach(::add) }
                .build()
            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .apply {
                    if (!privileged) {
                        remove(Player.COMMAND_SET_MEDIA_ITEM)
                        remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
                    }
                }
                .build()
            return MediaSession.ConnectionResult.accept(commands, playerCommands)
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (!controller.isTrusted && controller.packageName != packageName) {
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
            }
            val valid = when (customCommand) {
                PlaybackCommands.PLAY_EPISODE -> args.containsKey(PlaybackCommands.EXTRA_EPISODE_ID)
                PlaybackCommands.SET_SPEED -> args.getFloat(PlaybackCommands.EXTRA_SPEED, Float.NaN).isFinite()
                PlaybackCommands.SET_SKIP_SILENCE -> args.containsKey(PlaybackCommands.EXTRA_ENABLED)
                PlaybackCommands.START_SLEEP_TIMER -> args.getLong(PlaybackCommands.EXTRA_DURATION_MS, 0) > 0
                PlaybackCommands.CANCEL_SLEEP_TIMER -> true
                else -> false
            }
            if (!valid) return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
            scope.launch {
                when (customCommand) {
                    PlaybackCommands.PLAY_EPISODE -> manager.play(args.getLong(PlaybackCommands.EXTRA_EPISODE_ID))
                    PlaybackCommands.SET_SPEED -> manager.setSpeed(args.getFloat(PlaybackCommands.EXTRA_SPEED))
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
        const val TAG = "PodcastMediaSession"
        const val NOTIFICATION_CHANNEL_ID = "playback"
        const val SEEK_BACK_MS = 10_000L
        const val SEEK_FORWARD_MS = 30_000L
    }
}

/** Reads the HTTP status, if any, out of the player's error chain before reducing it to a [PlaybackFailure]. */
private fun failureOf(error: PlaybackException): PlaybackFailure {
    val status = generateSequence<Throwable>(error) { it.cause }
        .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
        .firstOrNull()?.responseCode
    return PlaybackFailure.fromErrorCode(error.errorCode, status)
}
