// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.sonora.core.database.EpisodeDao
import org.sonora.core.database.PodcastDao
import org.sonora.core.media.PlayQueue
import org.sonora.core.media.PlaybackFailure
import org.sonora.core.media.PlaybackPort
import org.sonora.core.media.PlaybackStateManager

data class PlayerEpisode(val id: Long, val title: String, val podcastTitle: String?, val artworkUrl: String?)

data class PlayerUiState(
    val episode: PlayerEpisode? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val bufferedMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val skipSilence: Boolean = false,
    val sleepTimerRemainingMs: Long? = null,
    val upNext: List<PlayerEpisode> = emptyList(),
)

sealed interface PlayerIntent {
    data object TogglePlayPause : PlayerIntent
    data class SeekTo(val positionMs: Long) : PlayerIntent
    data object SkipForward : PlayerIntent
    data object Rewind : PlayerIntent
    /** [direction] is +1 or -1 tenths of a speed step. */
    data class StepSpeed(val direction: Int) : PlayerIntent
    data class SetSpeed(val speed: Float) : PlayerIntent
    data object ToggleSkipSilence : PlayerIntent
    data class SetSleepTimer(val durationMs: Long) : PlayerIntent
    data object CancelSleepTimer : PlayerIntent
    data class PlayQueued(val episodeId: Long) : PlayerIntent
    data class RemoveFromQueue(val episodeId: Long) : PlayerIntent
}

sealed interface PlayerEvent {
    data class PlaybackFailed(val failure: PlaybackFailure) : PlayerEvent
}

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val port: PlaybackPort,
    private val episodeDao: EpisodeDao,
    private val podcastDao: PodcastDao,
    private val queue: PlayQueue,
) : ViewModel() {
    private val episodeCache = HashMap<Long, PlayerEpisode>()

    val uiState: StateFlow<PlayerUiState> = combine(port.state, port.progress(), queue.items, ::Triple)
        .map { (playback, progress, queued) ->
            PlayerUiState(
                episode = playback.episodeId?.let { resolve(it) },
                isPlaying = playback.isPlaying,
                positionMs = progress.positionMs,
                bufferedMs = progress.bufferedMs,
                durationMs = progress.durationMs,
                speed = playback.speed,
                skipSilence = playback.skipSilence,
                sleepTimerRemainingMs = playback.sleepTimerRemainingMs,
                upNext = queued.filter { it != playback.episodeId }.mapNotNull { resolve(it) },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PlayerUiState())

    /** Playback problems to tell the listener about, from whichever screen started playback. */
    val events: Flow<PlayerEvent> = port.errors.map { PlayerEvent.PlaybackFailed(it) }

    fun onIntent(intent: PlayerIntent) {
        val state = uiState.value
        when (intent) {
            PlayerIntent.TogglePlayPause -> launch {
                val id = state.episode?.id ?: return@launch
                if (state.isPlaying) port.pause() else port.play(id)
            }
            is PlayerIntent.SeekTo -> launch { port.seekTo(clamp(intent.positionMs, state.durationMs)) }
            PlayerIntent.SkipForward -> launch { port.seekTo(clamp(state.positionMs + SKIP_FORWARD_MS, state.durationMs)) }
            PlayerIntent.Rewind -> launch { port.seekTo(clamp(state.positionMs - REWIND_MS, state.durationMs)) }
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

    private fun clamp(positionMs: Long, durationMs: Long): Long =
        if (durationMs > 0) positionMs.coerceIn(0, durationMs) else positionMs.coerceAtLeast(0)

    private suspend fun resolve(id: Long): PlayerEpisode? = episodeCache[id] ?: run {
        val episode = episodeDao.findById(id) ?: return null
        val podcast = podcastDao.findById(episode.podcastId)
        PlayerEpisode(id, episode.title, podcast?.title, episode.imageUrl ?: podcast?.imageUrl).also { episodeCache[id] = it }
    }

    private companion object {
        const val SKIP_FORWARD_MS = 30_000L
        const val REWIND_MS = 15_000L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
