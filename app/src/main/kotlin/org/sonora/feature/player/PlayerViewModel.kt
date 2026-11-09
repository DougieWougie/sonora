// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.sonora.core.database.EpisodeDao
import org.sonora.core.download.EpisodeActions
import org.sonora.core.model.DownloadState
import org.sonora.core.database.PodcastDao
import org.sonora.core.media.PlayQueue
import org.sonora.core.media.PlaybackFailure
import org.sonora.core.media.PlaybackPort
import org.sonora.core.media.PlaybackProgress
import org.sonora.core.media.PlaybackSource
import org.sonora.core.media.PlaybackStateManager
import org.sonora.feature.settings.SettingsStore

data class PlayerEpisode(val id: Long, val title: String, val podcastTitle: String?, val artworkUrl: String?, val showNotes: String? = null, val link: String? = null)

data class PlayerUiState(
    val episode: PlayerEpisode? = null,
    val isPlaying: Boolean = false,
    val isAudioFlowing: Boolean = false,
    val speed: Float = 1f,
    val skipSilence: Boolean = false,
    val sleepTimerRemainingMs: Long? = null,
    val source: PlaybackSource? = null,
    val upNext: List<PlayerEpisode> = emptyList(),
    /** Where the loaded episode's download stands, for the chip that offers to fetch a streamed one. */
    val downloadState: DownloadState = DownloadState.NONE,
) {
    val hasNext: Boolean get() = upNext.isNotEmpty()
}

sealed interface PlayerIntent {
    data object TogglePlayPause : PlayerIntent
    data class SeekTo(val positionMs: Long) : PlayerIntent
    data object SkipForward : PlayerIntent
    data object Rewind : PlayerIntent
    data object Next : PlayerIntent
    data object Previous : PlayerIntent
    /** [direction] is +1 or -1 tenths of a speed step. */
    data class StepSpeed(val direction: Int) : PlayerIntent
    data class SetSpeed(val speed: Float) : PlayerIntent
    data object ToggleSkipSilence : PlayerIntent
    data class SetSleepTimer(val durationMs: Long) : PlayerIntent
    data object CancelSleepTimer : PlayerIntent
    data class PlayQueued(val episodeId: Long) : PlayerIntent
    data class RemoveFromQueue(val episodeId: Long) : PlayerIntent
    data class MoveInQueue(val episodeId: Long, val toIndex: Int) : PlayerIntent
    /** Share what is playing; whether a link goes with it is the user's setting at the moment of the tap. */
    data object Share : PlayerIntent
    /** Download what is playing; playback moves onto the file when it lands. */
    data object DownloadCurrent : PlayerIntent
}

sealed interface PlayerEvent {
    data class PlaybackFailed(val failure: PlaybackFailure) : PlayerEvent
    data class Share(val subject: String, val text: String) : PlayerEvent
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val port: PlaybackPort,
    private val episodeDao: EpisodeDao,
    private val podcastDao: PodcastDao,
    private val queue: PlayQueue,
    private val settings: SettingsStore,
    private val actions: EpisodeActions,
) : ViewModel() {
    private val episodeCache = HashMap<Long, PlayerEpisode>()

    private val downloadState: Flow<DownloadState> = port.state.map { it.episodeId }.distinctUntilChanged()
        .flatMapLatest { id ->
            if (id == null) flowOf(DownloadState.NONE) else episodeDao.observeById(id).map { it?.downloadState ?: DownloadState.NONE }
        }

    /**
     * The 500 ms position poll lives apart from [uiState] on purpose: only the scrubber and the mini-player's
     * bar read it, so the rest of the app does not recompose twice a second during playback.
     */
    val progress: StateFlow<PlaybackProgress> = port.progress()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PlaybackProgress.None)

    val uiState: StateFlow<PlayerUiState> = combine(port.state, queue.items, downloadState, ::Triple)
        .map { (playback, queued, download) ->
            PlayerUiState(
                episode = playback.episodeId?.let { resolve(it) },
                isPlaying = playback.isPlaying,
                isAudioFlowing = playback.isAudioFlowing,
                speed = playback.speed,
                skipSilence = playback.skipSilence,
                sleepTimerRemainingMs = playback.sleepTimerRemainingMs,
                source = playback.source,
                upNext = queued.filter { it != playback.episodeId }.mapNotNull { resolve(it) },
                downloadState = download,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PlayerUiState())

    /** Playback problems to tell the listener about, from whichever screen started playback. */
    private val shares = Channel<PlayerEvent.Share>(Channel.BUFFERED)
    val events: Flow<PlayerEvent> = merge(port.errors.map { PlayerEvent.PlaybackFailed(it) }, shares.receiveAsFlow())

    fun onIntent(intent: PlayerIntent) {
        val state = uiState.value
        when (intent) {
            PlayerIntent.TogglePlayPause -> launch {
                val id = state.episode?.id ?: return@launch
                if (state.isPlaying) port.pause() else port.play(id)
            }
            is PlayerIntent.SeekTo -> launch { port.seekTo(clamp(intent.positionMs, currentProgress().durationMs)) }
            PlayerIntent.SkipForward -> launch {
                val now = currentProgress()
                port.seekTo(clamp(now.positionMs + SKIP_FORWARD_MS, now.durationMs))
            }
            PlayerIntent.Rewind -> launch {
                val now = currentProgress()
                port.seekTo(clamp(now.positionMs - REWIND_MS, now.durationMs))
            }
            PlayerIntent.Next -> launch { port.next() }
            PlayerIntent.Previous -> launch { port.previous() }
            is PlayerIntent.StepSpeed -> {
                val tenths = (state.speed * 10).roundToInt() + intent.direction
                port.setSpeed((tenths / 10f).coerceIn(PlaybackStateManager.MIN_SPEED, PlaybackStateManager.MAX_SPEED))
            }
            is PlayerIntent.SetSpeed -> port.setSpeed(intent.speed.coerceIn(PlaybackStateManager.MIN_SPEED, PlaybackStateManager.MAX_SPEED))
            PlayerIntent.ToggleSkipSilence -> port.setSkipSilence(!state.skipSilence)
            is PlayerIntent.SetSleepTimer -> port.startSleepTimer(intent.durationMs)
            PlayerIntent.CancelSleepTimer -> port.cancelSleepTimer()
            is PlayerIntent.PlayQueued -> launch { port.play(intent.episodeId) }
            is PlayerIntent.RemoveFromQueue -> queue.remove(intent.episodeId)
            is PlayerIntent.MoveInQueue -> queue.move(intent.episodeId, intent.toIndex)
            PlayerIntent.Share -> state.episode?.let {
                shares.trySend(PlayerEvent.Share(it.title, shareText(it, settings.shareLink)))
            }
            PlayerIntent.DownloadCurrent -> state.episode?.let { launch { actions.download(it.id) } }
        }
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // Already reported through [events]; the UI keeps its last state.
            }
        }
    }

    /** The latest position, read when needed rather than held, so seeks are right even with nobody watching [progress]. */
    private suspend fun currentProgress(): PlaybackProgress = port.progress().first()

    private fun clamp(positionMs: Long, durationMs: Long): Long =
        if (durationMs > 0) positionMs.coerceIn(0, durationMs) else positionMs.coerceAtLeast(0)

    private suspend fun resolve(id: Long): PlayerEpisode? = episodeCache[id] ?: run {
        val episode = episodeDao.findById(id) ?: return null
        val podcast = podcastDao.findById(episode.podcastId)
        PlayerEpisode(id, episode.title, podcast?.title, episode.imageUrl ?: podcast?.imageUrl, episode.description, episode.link ?: podcast?.link).also { episodeCache[id] = it }
    }

    private companion object {
        const val SKIP_FORWARD_MS = 30_000L
        const val REWIND_MS = 10_000L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
