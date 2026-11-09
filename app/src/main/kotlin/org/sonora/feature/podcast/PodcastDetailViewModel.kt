// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.podcast

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.sonora.core.database.EpisodeDao
import org.sonora.core.database.EpisodeEntity
import org.sonora.core.database.PodcastDao
import org.sonora.core.database.PodcastEntity
import org.sonora.core.database.SubscriptionRepository
import org.sonora.core.download.EpisodeActions
import org.sonora.core.media.PlayQueue
import org.sonora.core.media.PlaybackPort
import org.sonora.core.model.DownloadState
import org.sonora.core.work.SyncController
import org.sonora.feature.common.EpisodeItem
import org.sonora.feature.common.PendingMarkOlder
import org.sonora.feature.common.PlayChoice
import org.sonora.feature.common.toItem
import org.sonora.core.settings.SettingsStore
import org.sonora.core.text.htmlToSearchText

enum class EpisodeFilter { ALL, UNPLAYED, DOWNLOADED }

data class PodcastHeader(
    val id: Long,
    val feedUrl: String,
    val title: String,
    val author: String?,
    val description: String?,
    val imageUrl: String?,
    val autoDownload: Boolean,
    val notifyNewEpisodes: Boolean = true,
)

data class PodcastDetailUiState(
    val podcast: PodcastHeader? = null,
    val subscribed: Boolean = true,
    val episodes: List<EpisodeItem> = emptyList(),
    val filter: EpisodeFilter = EpisodeFilter.ALL,
    val query: String = "",
    val pendingMarkOlder: PendingMarkOlder? = null,
    /** An episode waiting for the user to say whether it plays next or goes last. */
    val pendingPlay: Long? = null,
    /** Developer actions are on in Settings → Advanced (and only exist in debug builds). */
    val debugTools: Boolean = false,
    /** Episodes picked for a bulk action; selection mode is on while this is not empty. */
    val selectedIds: Set<Long> = emptySet(),
    val isRefreshing: Boolean = false,
    val isLoading: Boolean = true,
)

sealed interface PodcastDetailIntent {
    data class SetFilter(val filter: EpisodeFilter) : PodcastDetailIntent
    data class SetQuery(val query: String) : PodcastDetailIntent
    data class Play(val episodeId: Long) : PodcastDetailIntent
    data object ConfirmPlayNext : PodcastDetailIntent
    data object ConfirmAddToEnd : PodcastDetailIntent
    data object DismissPlayChoice : PodcastDetailIntent
    data class Enqueue(val episodeId: Long) : PodcastDetailIntent
    data class Dequeue(val episodeId: Long) : PodcastDetailIntent
    data class PlayNext(val episodeId: Long) : PodcastDetailIntent
    data class Download(val episodeId: Long) : PodcastDetailIntent
    data class DeleteDownload(val episodeId: Long) : PodcastDetailIntent
    data class SetPlayed(val episodeId: Long, val played: Boolean) : PodcastDetailIntent
    data class RequestMarkOlderPlayed(val episodeId: Long) : PodcastDetailIntent
    data object ConfirmMarkOlderPlayed : PodcastDetailIntent
    data object DismissMarkOlderPlayed : PodcastDetailIntent
    data class ToggleSelect(val episodeId: Long) : PodcastDetailIntent
    data object SelectAll : PodcastDetailIntent
    data object ClearSelection : PodcastDetailIntent
    data class SetSelectedPlayed(val played: Boolean) : PodcastDetailIntent
    data object ToggleSubscription : PodcastDetailIntent
    data object ToggleAutoDownload : PodcastDetailIntent
    data object ToggleNotifications : PodcastDetailIntent

    /** Debug builds only: forget the newest episode so the next refresh finds it new again. */
    data object DropNewestEpisode : PodcastDetailIntent
    data object Refresh : PodcastDetailIntent
}

sealed interface PodcastDetailEvent {
    data class Message(val text: String) : PodcastDetailEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PodcastDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val podcastDao: PodcastDao,
    private val episodeDao: EpisodeDao,
    private val queue: PlayQueue,
    private val subscriptions: SubscriptionRepository,
    private val actions: EpisodeActions,
    private val sync: SyncController,
    private val playback: PlaybackPort,
    private val settings: SettingsStore,
) : ViewModel() {
    private data class Controls(
        val filter: EpisodeFilter = EpisodeFilter.ALL,
        val query: String = "",
        val pendingMarkOlder: PendingMarkOlder? = null,
        val selected: Set<Long> = emptySet(),
    )

    /** Changes when the user resubscribes: the new row gets a new id. */
    private val podcastId = MutableStateFlow(
        checkNotNull(savedStateHandle.get<Long>(ARG_PODCAST_ID)) { "Missing $ARG_PODCAST_ID" },
    )
    private val playChoice = PlayChoice(queue, playback)
    private val controls = MutableStateFlow(Controls())
    private val _events = Channel<PodcastDetailEvent>(Channel.BUFFERED)
    val events: Flow<PodcastDetailEvent> = _events.receiveAsFlow()

    /** Kept after unsubscribing so the screen can still offer "Subscribe". */
    private var lastHeader: PodcastHeader? = null

    val uiState: StateFlow<PodcastDetailUiState> = combine(
        podcastId.flatMapLatest { podcastDao.observeById(it) },
        podcastId.flatMapLatest { episodeDao.observeForPodcast(it) },
        controls,
        combine(queue.items, playChoice.pending, ::Pair),
        sync.syncing,
    ) { podcast, episodes, controls, (queued, pendingPlay), syncing ->
        val header = podcast?.toHeader()?.also { lastHeader = it } ?: lastHeader
        val subscribed = podcast != null
        val visible = if (subscribed) episodes.filter { it.matches(controls) } else emptyList()
        PodcastDetailUiState(
            podcast = header,
            subscribed = subscribed,
            episodes = visible.map { it.toItem(podcast, it.id in queued) },
            filter = controls.filter,
            query = controls.query,
            pendingMarkOlder = controls.pendingMarkOlder,
            pendingPlay = pendingPlay,
            debugTools = settings.debugTools,
            selectedIds = visible.map { it.id }.filterTo(mutableSetOf()) { it in controls.selected },
            isRefreshing = syncing,
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PodcastDetailUiState())

    fun onIntent(intent: PodcastDetailIntent) {
        when (intent) {
            is PodcastDetailIntent.SetFilter -> controls.update { it.copy(filter = intent.filter) }
            is PodcastDetailIntent.SetQuery -> controls.update { it.copy(query = intent.query) }
            is PodcastDetailIntent.Enqueue -> queue.enqueue(intent.episodeId)
            is PodcastDetailIntent.Dequeue -> queue.remove(intent.episodeId)
            is PodcastDetailIntent.PlayNext -> queue.enqueueNext(intent.episodeId)
            is PodcastDetailIntent.Play -> attempt { playChoice.request(intent.episodeId) }
            PodcastDetailIntent.ConfirmPlayNext -> playChoice.playNext()
            PodcastDetailIntent.ConfirmAddToEnd -> playChoice.addToEnd()
            PodcastDetailIntent.DismissPlayChoice -> playChoice.dismiss()
            is PodcastDetailIntent.Download -> attempt { actions.download(intent.episodeId) }
            is PodcastDetailIntent.DeleteDownload -> attempt { actions.deleteDownload(intent.episodeId) }
            is PodcastDetailIntent.SetPlayed -> attempt {
                if (intent.played) actions.markPlayed(intent.episodeId) else actions.markUnplayed(intent.episodeId)
            }
            is PodcastDetailIntent.RequestMarkOlderPlayed -> attempt {
                val pending = PendingMarkOlder(intent.episodeId, actions.countOlderUnplayed(intent.episodeId))
                controls.update { it.copy(pendingMarkOlder = pending) }
            }
            PodcastDetailIntent.ConfirmMarkOlderPlayed -> {
                val pending = controls.value.pendingMarkOlder
                controls.update { it.copy(pendingMarkOlder = null) }
                if (pending != null && pending.count > 0) attempt { actions.markOlderPlayed(pending.episodeId) }
            }
            PodcastDetailIntent.DismissMarkOlderPlayed -> controls.update { it.copy(pendingMarkOlder = null) }
            is PodcastDetailIntent.ToggleSelect -> controls.update {
                it.copy(selected = if (intent.episodeId in it.selected) it.selected - intent.episodeId else it.selected + intent.episodeId)
            }
            PodcastDetailIntent.SelectAll -> controls.update { it.copy(selected = uiState.value.episodes.map { e -> e.id }.toSet()) }
            PodcastDetailIntent.ClearSelection -> controls.update { it.copy(selected = emptySet()) }
            is PodcastDetailIntent.SetSelectedPlayed -> {
                val ids = uiState.value.selectedIds
                controls.update { it.copy(selected = emptySet()) }
                attempt {
                    ids.forEach { if (intent.played) actions.markPlayed(it) else actions.markUnplayed(it) }
                }
            }
            PodcastDetailIntent.ToggleSubscription -> attempt { toggleSubscription() }
            PodcastDetailIntent.ToggleAutoDownload -> attempt {
                podcastDao.findById(podcastId.value)?.let { podcastDao.update(it.copy(autoDownload = !it.autoDownload)) }
            }
            PodcastDetailIntent.ToggleNotifications -> attempt {
                podcastDao.findById(podcastId.value)?.let { podcastDao.update(it.copy(notifyNewEpisodes = !it.notifyNewEpisodes)) }
            }
            PodcastDetailIntent.DropNewestEpisode -> attempt {
                episodeDao.getForPodcast(podcastId.value)
                    .maxByOrNull { it.publishedAtMillis ?: Long.MIN_VALUE }
                    ?.let { episodeDao.deleteById(it.id) }
                sync.syncNow()
                if (podcastDao.findById(podcastId.value)?.notifyNewEpisodes == false) {
                    _events.trySend(PodcastDetailEvent.Message("Notifications are off for this show, so it will not notify"))
                }
            }
            PodcastDetailIntent.Refresh -> sync.syncNow()
        }
    }

    private suspend fun toggleSubscription() {
        val header = lastHeader ?: return
        if (uiState.value.subscribed) {
            subscriptions.unsubscribe(header.feedUrl)
        } else {
            subscriptions.subscribe(header.feedUrl, header.title)
            podcastDao.findByFeedUrl(header.feedUrl)?.let { podcastId.value = it.id }
            sync.syncNow()
        }
    }

    private fun attempt(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.trySend(PodcastDetailEvent.Message(e.message ?: "Something went wrong"))
            }
        }
    }

    private fun EpisodeEntity.matches(controls: Controls): Boolean {
        val filterOk = when (controls.filter) {
            EpisodeFilter.ALL -> true
            EpisodeFilter.UNPLAYED -> !played
            EpisodeFilter.DOWNLOADED -> downloadState == DownloadState.DOWNLOADED
        }
        val q = controls.query.trim()
        return filterOk && (q.isEmpty() || title.contains(q, ignoreCase = true) || description?.let { htmlToSearchText(it) }.orEmpty().contains(q, ignoreCase = true))
    }

    private fun PodcastEntity.toHeader() = PodcastHeader(id, feedUrl, title, author, description, imageUrl, autoDownload, notifyNewEpisodes)

    companion object {
        const val ARG_PODCAST_ID = "podcastId"
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
