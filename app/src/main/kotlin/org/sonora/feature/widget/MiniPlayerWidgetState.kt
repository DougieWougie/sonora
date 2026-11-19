// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.widget

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import org.sonora.core.database.EpisodeDao
import org.sonora.core.database.PodcastDao
import org.sonora.core.media.PlaybackState

/** What the home-screen mini player shows. */
sealed interface MiniPlayerWidgetState {
    /** Nothing is loaded in the player. */
    data object Idle : MiniPlayerWidgetState

    data class Loaded(
        val episodeId: Long,
        val episodeTitle: String,
        val podcastTitle: String?,
        val artworkUrl: String?,
        val isPlaying: Boolean,
    ) : MiniPlayerWidgetState
}

/**
 * Widget states derived from [playback]. Only the loaded episode and play/pause matter to the widget, so
 * progress ticks, speed and the sleep timer never cause a redraw.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun miniPlayerWidgetStates(
    playback: Flow<PlaybackState>,
    episodeDao: EpisodeDao,
    podcastDao: PodcastDao,
): Flow<MiniPlayerWidgetState> = playback
    .map { it.episodeId to it.isPlaying }
    .distinctUntilChanged()
    .flatMapLatest { (episodeId, isPlaying) ->
        flow {
            val episode = episodeId?.let { episodeDao.findById(it) }
            if (episode == null) {
                emit(MiniPlayerWidgetState.Idle)
            } else {
                val podcast = podcastDao.findById(episode.podcastId)
                emit(
                    MiniPlayerWidgetState.Loaded(
                        episodeId = episode.id,
                        episodeTitle = episode.title,
                        podcastTitle = podcast?.title,
                        artworkUrl = episode.imageUrl ?: podcast?.imageUrl,
                        isPlaying = isPlaying,
                    ),
                )
            }
        }
    }
    .distinctUntilChanged()

/**
 * Calls [redraw] for each state; a newer state cancels a redraw still in flight, so one that hangs cannot hold
 * up the next. A failed redraw (the launcher went away mid-update, say) is dropped so the
 * next state still gets drawn (it is passed to [onFailure]); cancellation is not.
 */
suspend fun Flow<MiniPlayerWidgetState>.redrawOnChange(
    onFailure: (Exception) -> Unit = {},
    redraw: suspend () -> Unit,
) = collectLatest {
    try {
        redraw()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // The next state change redraws.
        onFailure(e)
    }
}
