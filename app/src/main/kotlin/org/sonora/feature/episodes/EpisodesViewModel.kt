// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.episodes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.getAndUpdate
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
import org.sonora.feature.common.PendingMarkOlder
import org.sonora.feature.common.toItem

data class EpisodesUiState(
    val inbox: List<EpisodeItem> = emptyList(),
    val queue: List<EpisodeItem> = emptyList(),
    val pendingMarkOlder: PendingMarkOlder? = null,
    val confirmingClear: Boolean = false,
    val isLoading: Boolean = true,
)

sealed interface EpisodesIntent {
    data class Play(val episodeId: Long) : EpisodesIntent
    data class Enqueue(val episodeId: Long) : EpisodesIntent
    data class Dequeue(val episodeId: Long) : EpisodesIntent
    data class PlayNext(val episodeId: Long) : EpisodesIntent
    data class MoveInQueue(val episodeId: Long, val toIndex: Int) : EpisodesIntent
    /** Puts the queue back as it was, e.g. to undo a removal. */
    data class RestoreQueue(val episodeIds: List<Long>) : EpisodesIntent
    data object RequestClearQueue : EpisodesIntent
    data object DismissClearQueue : EpisodesIntent
    /** Clears for real; [RequestClearQueue] is the one that asks first. */
    data object ClearQueue : EpisodesIntent
    data object PlayQueue : EpisodesIntent
    data object ShuffleQueue : EpisodesIntent
    data class Download(val episodeId: Long) : EpisodesIntent
    data class DeleteDownload(val episodeId: Long) : EpisodesIntent
    data class SetPlayed(val episodeId: Long, val played: Boolean) : EpisodesIntent
    data class RequestMarkOlderPlayed(val episodeId: Long) : EpisodesIntent
    data object ConfirmMarkOlderPlayed : EpisodesIntent
    data object DismissMarkOlderPlayed : EpisodesIntent
}

sealed interface EpisodesEvent {
    data class Message(val text: String) : EpisodesEvent
    /** The queue lost something; [previous] is its order before, so the screen can offer to undo. */
    data class QueueChanged(val text: String, val previous: List<Long>) : EpisodesEvent
}

@HiltViewModel
class EpisodesViewModel @Inject constructor(
    private val episodeDao: EpisodeDao,
    podcastDao: PodcastDao,
    private val queue: PlayQueue,
    private val actions: EpisodeActions,
    private val playback: PlaybackPort,
) : ViewModel() {
    private val pendingMarkOlder = MutableStateFlow<PendingMarkOlder?>(null)
    private val confirmingClear = MutableStateFlow(false)
    private val _events = Channel<EpisodesEvent>(Channel.BUFFERED)
    val events: Flow<EpisodesEvent> = _events.receiveAsFlow()

    val uiState: StateFlow<EpisodesUiState> = combine(
        episodeDao.observeUnplayed(INBOX_LIMIT),
        podcastDao.observeAll(),
        queue.items,
        pendingMarkOlder,
        confirmingClear,
    ) { unplayed, podcasts, queued, pending, confirming ->
        val byId = podcasts.associateBy { it.id }
        val queuedSet = queued.toSet()
        EpisodesUiState(
            inbox = unplayed.map { it.toItem(byId[it.podcastId], it.id in queuedSet) },
            queue = queued.mapNotNull { id -> episodeDao.findById(id)?.let { it.toItem(byId[it.podcastId], true) } },
            pendingMarkOlder = pending,
            confirmingClear = confirming,
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), EpisodesUiState())

    fun onIntent(intent: EpisodesIntent) {
        when (intent) {
            is EpisodesIntent.Enqueue -> queue.enqueue(intent.episodeId)
            is EpisodesIntent.Dequeue -> {
                val previous = queue.items.value
                queue.remove(intent.episodeId)
                if (previous != queue.items.value) _events.trySend(EpisodesEvent.QueueChanged("Removed from Up next", previous))
            }
            is EpisodesIntent.PlayNext -> queue.enqueueNext(intent.episodeId)
            is EpisodesIntent.MoveInQueue -> queue.move(intent.episodeId, intent.toIndex)
            is EpisodesIntent.RestoreQueue -> queue.replaceAll(intent.episodeIds)
            EpisodesIntent.RequestClearQueue -> confirmingClear.value = queue.items.value.isNotEmpty()
            EpisodesIntent.DismissClearQueue -> confirmingClear.value = false
            EpisodesIntent.ClearQueue -> { queue.clear(); confirmingClear.value = false }
            EpisodesIntent.PlayQueue -> attempt { queue.items.value.firstOrNull()?.let { playback.play(it) } }
            EpisodesIntent.ShuffleQueue -> attempt {
                queue.replaceAll(queue.items.value.shuffled())
                queue.items.value.firstOrNull()?.let { playback.play(it) }
            }
            is EpisodesIntent.Play -> attempt { playback.play(intent.episodeId) }
            is EpisodesIntent.Download -> attempt { actions.download(intent.episodeId) }
            is EpisodesIntent.DeleteDownload -> attempt { actions.deleteDownload(intent.episodeId) }
            is EpisodesIntent.SetPlayed -> attempt {
                if (intent.played) actions.markPlayed(intent.episodeId) else actions.markUnplayed(intent.episodeId)
            }
            is EpisodesIntent.RequestMarkOlderPlayed -> attempt {
                pendingMarkOlder.value = PendingMarkOlder(intent.episodeId, actions.countOlderUnplayed(intent.episodeId))
            }
            EpisodesIntent.ConfirmMarkOlderPlayed -> {
                val pending = pendingMarkOlder.getAndUpdate { null }
                if (pending != null && pending.count > 0) attempt { actions.markOlderPlayed(pending.episodeId) }
            }
            EpisodesIntent.DismissMarkOlderPlayed -> pendingMarkOlder.value = null
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
