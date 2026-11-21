// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.widget

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import org.sonora.core.database.EpisodeDao
import org.sonora.core.database.PodcastDao
import org.sonora.core.media.PlaybackState

/** How many queued episodes the large widget has room to list. */
const val MAX_QUEUE_ROWS = 4

/** Where the widget keeps the episode that was last loaded, so it can offer it again once the player is empty. */
interface LastPlayedStore {
    fun recall(): Long?
    fun remember(episodeId: Long)
}

/** Keeps the last played episode id in SharedPreferences. */
class PrefsLastPlayedStore(context: android.content.Context) : LastPlayedStore {
    private val prefs = context.applicationContext.getSharedPreferences("widget", android.content.Context.MODE_PRIVATE)

    override fun recall(): Long? = prefs.getLong(KEY, NONE).takeIf { it != NONE }

    override fun remember(episodeId: Long) {
        prefs.edit().putLong(KEY, episodeId).apply()
    }

    private companion object {
        const val KEY = "last_played"
        const val NONE = -1L
    }
}

data class QueuedEpisode(val episodeId: Long, val title: String)

/** What the home-screen mini player shows. */
sealed interface MiniPlayerWidgetState {
    /** Nothing is loaded in the player and nothing has been played before. */
    data object Idle : MiniPlayerWidgetState

    data class Loaded(
        val episodeId: Long,
        val episodeTitle: String,
        val podcastTitle: String?,
        val artworkUrl: String?,
        val isPlaying: Boolean,
        /** False when the player is empty and this is the episode last played, offered for resuming. */
        val active: Boolean = true,
        /** The first [MAX_QUEUE_ROWS] queued episodes. */
        val queue: List<QueuedEpisode> = emptyList(),
        /** Everything queued, including what [queue] leaves out. */
        val queueSize: Int = queue.size,
    ) : MiniPlayerWidgetState
}

/**
 * Widget states derived from [playback] and the [queue]. Only the loaded episode, play/pause and the queue
 * matter to the widget, so progress ticks, speed and the sleep timer never cause a redraw. With nothing
 * loaded the episode in [lastPlayed] is offered instead, so the widget is not blank between sessions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun miniPlayerWidgetStates(
    playback: Flow<PlaybackState>,
    queue: Flow<List<Long>>,
    episodeDao: EpisodeDao,
    podcastDao: PodcastDao,
    lastPlayed: LastPlayedStore,
): Flow<MiniPlayerWidgetState> = combine(
    playback.map { it.episodeId to it.isPlaying }.distinctUntilChanged(),
    queue.distinctUntilChanged(),
) { current, queued -> current to queued }
    .flatMapLatest { (current, queued) ->
        val (loadedId, isPlaying) = current
        flow {
            loadedId?.let(lastPlayed::remember)
            val episodeId = loadedId ?: lastPlayed.recall()
            val episode = episodeId?.let { episodeDao.findById(it) }
            if (episode == null) {
                emit(MiniPlayerWidgetState.Idle)
            } else {
                val podcast = podcastDao.findById(episode.podcastId)
                val shown = mutableListOf<QueuedEpisode>()
                for (id in queued) {
                    if (shown.size == MAX_QUEUE_ROWS) break
                    episodeDao.findById(id)?.let { shown += QueuedEpisode(it.id, it.title) }
                }
                emit(
                    MiniPlayerWidgetState.Loaded(
                        episodeId = episode.id,
                        episodeTitle = episode.title,
                        podcastTitle = podcast?.title,
                        artworkUrl = episode.imageUrl ?: podcast?.imageUrl,
                        isPlaying = isPlaying && loadedId != null,
                        active = loadedId != null,
                        queue = shown,
                        queueSize = queued.size,
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
