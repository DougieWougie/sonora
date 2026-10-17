// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.episodes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.sonora.core.database.EpisodeDao
import org.sonora.core.database.PodcastDao
import org.sonora.core.download.EpisodeActions
import org.sonora.core.media.PlayQueue
import org.sonora.core.media.PlaybackPort
import org.sonora.feature.common.EpisodeItem
import org.sonora.feature.common.toItem

data class EpisodesUiState(
    val inbox: List<EpisodeItem> = emptyList(),
    val queue: List<EpisodeItem> = emptyList(),
    val isLoading: Boolean = true,
)

sealed interface EpisodesIntent {
    data class Play(val episodeId: Long) : EpisodesIntent
    data class Enqueue(val episodeId: Long) : EpisodesIntent
    data class Dequeue(val episodeId: Long) : EpisodesIntent
    data object ClearQueue : EpisodesIntent
    data class Download(val episodeId: Long) : EpisodesIntent
    data class DeleteDownload(val episodeId: Long) : EpisodesIntent
    data class SetPlayed(val episodeId: Long, val played: Boolean) : EpisodesIntent
}

sealed interface EpisodesEvent {
    data class Message(val text: String) : EpisodesEvent
}

@HiltViewModel
class EpisodesViewModel @Inject constructor(
    private val episodeDao: EpisodeDao,
    podcastDao: PodcastDao,
    private val queue: PlayQueue,
    private val actions: EpisodeActions,
    private val playback: PlaybackPort,
) : ViewModel() {
    private val _events = Channel<EpisodesEvent>(Channel.BUFFERED)
    val events: Flow<EpisodesEvent> = _events.receiveAsFlow()

    val uiState: StateFlow<EpisodesUiState> = combine(
        episodeDao.observeUnplayed(INBOX_LIMIT),
        podcastDao.observeAll(),
        queue.items,
    ) { unplayed, podcasts, queued ->
        val byId = podcasts.associateBy { it.id }
        val queuedSet = queued.toSet()
        EpisodesUiState(
            inbox = unplayed.map { it.toItem(byId[it.podcastId], it.id in queuedSet) },
            queue = queued.mapNotNull { id -> episodeDao.findById(id)?.let { it.toItem(byId[it.podcastId], true) } },
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), EpisodesUiState())

    fun onIntent(intent: EpisodesIntent) {
        when (intent) {
            is EpisodesIntent.Enqueue -> queue.enqueue(intent.episodeId)
            is EpisodesIntent.Dequeue -> queue.remove(intent.episodeId)
            EpisodesIntent.ClearQueue -> queue.clear()
            is EpisodesIntent.Play -> attempt { playback.play(intent.episodeId) }
            is EpisodesIntent.Download -> attempt { actions.download(intent.episodeId) }
            is EpisodesIntent.DeleteDownload -> attempt { actions.deleteDownload(intent.episodeId) }
            is EpisodesIntent.SetPlayed -> attempt {
                if (intent.played) actions.markPlayed(intent.episodeId) else actions.markUnplayed(intent.episodeId)
            }
        }
    }

    private fun attempt(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.trySend(EpisodesEvent.Message(e.message ?: "Something went wrong"))
            }
        }
    }

    private companion object {
        const val INBOX_LIMIT = 100
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
