// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.queue

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
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.sonora.core.database.EpisodeDao
import org.sonora.core.database.PodcastDao
import org.sonora.core.database.PodcastEntity
import org.sonora.core.download.EpisodeActions
import org.sonora.core.media.PlayQueue
import org.sonora.core.media.PlaybackPort
import org.sonora.feature.common.EpisodeItem
import org.sonora.feature.common.PendingMarkOlder
import org.sonora.feature.common.PlayChoice
import org.sonora.feature.common.toItem

/** How many unplayed episodes the New segment shows at most. */
const val NEW_EPISODES_LIMIT = 100

data class QueueUiState(
    val queue: List<EpisodeItem> = emptyList(),
    /** Unplayed episodes from every subscription, newest first. */
    val new: List<EpisodeItem> = emptyList(),
    val pendingMarkOlder: PendingMarkOlder? = null,
    val confirmingClear: Boolean = false,
    /** An episode from New waiting for the user to say whether it plays next or goes last. */
    val pendingPlay: Long? = null,
    val isLoading: Boolean = true,
)

sealed interface QueueIntent {
    /** Tap on an episode outside the queue: asks where it goes if the queue has episodes. */
    data class Play(val episodeId: Long) : QueueIntent
    /** Tap on an episode that is already in the queue: plays it straight away. */
    data class PlayQueued(val episodeId: Long) : QueueIntent
    data object ConfirmPlayNext : QueueIntent
    data object ConfirmAddToEnd : QueueIntent
    data object DismissPlayChoice : QueueIntent
    data class Enqueue(val episodeId: Long) : QueueIntent
    data class Dequeue(val episodeId: Long) : QueueIntent
    data class PlayNext(val episodeId: Long) : QueueIntent
    data class MoveInQueue(val episodeId: Long, val toIndex: Int) : QueueIntent
    /** Puts the queue back as it was, e.g. to undo a removal. */
    data class RestoreQueue(val episodeIds: List<Long>) : QueueIntent
    data object RequestClearQueue : QueueIntent
    data object DismissClearQueue : QueueIntent
    /** Clears for real; [RequestClearQueue] is the one that asks first. */
    data object ClearQueue : QueueIntent
    data object PlayQueue : QueueIntent
    data object ShuffleQueue : QueueIntent
    data class Download(val episodeId: Long) : QueueIntent
    data class DeleteDownload(val episodeId: Long) : QueueIntent
    data class SetPlayed(val episodeId: Long, val played: Boolean) : QueueIntent
    data class RequestMarkOlderPlayed(val episodeId: Long) : QueueIntent
    data object ConfirmMarkOlderPlayed : QueueIntent
    data object DismissMarkOlderPlayed : QueueIntent
}

sealed interface QueueEvent {
    data class Message(val text: String) : QueueEvent
    /** The queue lost something; [previous] is its order before, so the screen can offer to undo. */
    data class QueueChanged(val text: String, val previous: List<Long>) : QueueEvent
}

@HiltViewModel
class QueueViewModel @Inject constructor(
    private val episodeDao: EpisodeDao,
    podcastDao: PodcastDao,
    private val queue: PlayQueue,
    private val actions: EpisodeActions,
    private val playback: PlaybackPort,
) : ViewModel() {
    private val playChoice = PlayChoice(queue, playback)
    private val pendingMarkOlder = MutableStateFlow<PendingMarkOlder?>(null)
    private val confirmingClear = MutableStateFlow(false)
    private val _events = Channel<QueueEvent>(Channel.BUFFERED)
    val events: Flow<QueueEvent> = _events.receiveAsFlow()

    /** Everything besides the lists themselves, bundled so [uiState] stays within combine's arity. */
    private data class Chrome(
        val pendingMarkOlder: PendingMarkOlder?,
        val confirmingClear: Boolean,
        val pendingPlay: Long?,
    )

    val uiState: StateFlow<QueueUiState> = combine(
        podcastDao.observeAll(),
        queue.items,
        episodeDao.observeUnplayed(NEW_EPISODES_LIMIT),
        combine(pendingMarkOlder, confirmingClear, playChoice.pending, ::Chrome),
    ) { podcasts, queued, unplayed, chrome ->
        val byId = podcasts.associateBy { it.id }
        val queuedIds = queued.toSet()
        QueueUiState(
            queue = queuedItems(queued, byId),
            new = unplayed.map { it.toItem(byId[it.podcastId], it.id in queuedIds) },
            pendingMarkOlder = chrome.pendingMarkOlder,
            confirmingClear = chrome.confirmingClear,
            pendingPlay = chrome.pendingPlay,
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), QueueUiState())

    /** One batched query (chunked under SQLite's variable limit) re-ordered to the queue, instead of one lookup per id. */
    private suspend fun queuedItems(queued: List<Long>, podcasts: Map<Long, PodcastEntity>): List<EpisodeItem> {
        val rows = queued.chunked(QUERY_CHUNK).flatMap { episodeDao.getRowsByIds(it) }.associateBy { it.id }
        return queued.mapNotNull { id -> rows[id]?.let { it.toItem(podcasts[it.podcastId], true) } }
    }

    fun onIntent(intent: QueueIntent) {
        when (intent) {
            is QueueIntent.Enqueue -> queue.enqueue(intent.episodeId)
            is QueueIntent.Dequeue -> {
                val previous = queue.items.value
                queue.remove(intent.episodeId)
                if (previous != queue.items.value) _events.trySend(QueueEvent.QueueChanged("Removed from Queue", previous))
            }
            is QueueIntent.PlayNext -> queue.enqueueNext(intent.episodeId)
            is QueueIntent.MoveInQueue -> queue.move(intent.episodeId, intent.toIndex)
            is QueueIntent.RestoreQueue -> queue.replaceAll(intent.episodeIds)
            QueueIntent.RequestClearQueue -> confirmingClear.value = queue.items.value.isNotEmpty()
            QueueIntent.DismissClearQueue -> confirmingClear.value = false
            QueueIntent.ClearQueue -> { confirmingClear.value = false; queue.clear() }
            QueueIntent.PlayQueue -> attempt { queue.items.value.firstOrNull()?.let { playback.play(it) } }
            QueueIntent.ShuffleQueue -> attempt {
                queue.replaceAll(queue.items.value.shuffled())
                queue.items.value.firstOrNull()?.let { playback.play(it) }
            }
            is QueueIntent.Play -> attempt { playChoice.request(intent.episodeId) }
            is QueueIntent.PlayQueued -> attempt { playback.play(intent.episodeId) }
            QueueIntent.ConfirmPlayNext -> playChoice.playNext()
            QueueIntent.ConfirmAddToEnd -> playChoice.addToEnd()
            QueueIntent.DismissPlayChoice -> playChoice.dismiss()
            is QueueIntent.Download -> attempt { actions.download(intent.episodeId) }
            is QueueIntent.DeleteDownload -> attempt { actions.deleteDownload(intent.episodeId) }
            is QueueIntent.SetPlayed -> attempt {
                if (intent.played) actions.markPlayed(intent.episodeId) else actions.markUnplayed(intent.episodeId)
            }
            is QueueIntent.RequestMarkOlderPlayed -> attempt {
                pendingMarkOlder.value = PendingMarkOlder(intent.episodeId, actions.countOlderUnplayed(intent.episodeId))
            }
            QueueIntent.ConfirmMarkOlderPlayed -> {
                val pending = pendingMarkOlder.getAndUpdate { null }
                if (pending != null && pending.count > 0) attempt { actions.markOlderPlayed(pending.episodeId) }
            }
            QueueIntent.DismissMarkOlderPlayed -> pendingMarkOlder.value = null
        }
    }

    private fun attempt(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.trySend(QueueEvent.Message(e.message ?: "Something went wrong"))
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val QUERY_CHUNK = 500
    }
}
