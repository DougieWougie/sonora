// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import org.sonora.core.database.EpisodeDao
import org.sonora.core.database.PodcastDao
import org.sonora.core.database.PodcastEntity
import org.sonora.core.database.PodcastEpisodeStats
import org.sonora.core.database.SubscriptionRepository
import org.sonora.core.download.EpisodeActions
import org.sonora.core.media.PlayQueue
import org.sonora.core.work.SyncController

enum class LibraryLayout {
    GRID, LIST, COMPACT;

    /** The order the layout button steps through. */
    fun next(): LibraryLayout = entries[(ordinal + 1) % entries.size]
}
enum class LibrarySort { TITLE, RECENT, UNPLAYED }

data class PodcastItem(
    val id: Long,
    val feedUrl: String,
    val title: String,
    val author: String?,
    val imageUrl: String?,
    val unplayedCount: Int,
    val autoDownload: Boolean,
)

data class SubscriptionsUiState(
    val podcasts: List<PodcastItem> = emptyList(),
    val layout: LibraryLayout = LibraryLayout.GRID,
    val sort: LibrarySort = LibrarySort.TITLE,
    val unplayedOnly: Boolean = false,
    /** What the user has typed into the library search; empty when not searching. */
    val query: String = "",
    /** The show the user is being asked to confirm unsubscribing from. */
    val confirmingUnsubscribe: PodcastItem? = null,
    val isRefreshing: Boolean = false,
    val queueSize: Int = 0,
    val isLoading: Boolean = true,
)

sealed interface SubscriptionsIntent {
    data class SetLayout(val layout: LibraryLayout) : SubscriptionsIntent
    data class SetSort(val sort: LibrarySort) : SubscriptionsIntent
    data class SetUnplayedOnly(val enabled: Boolean) : SubscriptionsIntent
    data class SetQuery(val query: String) : SubscriptionsIntent
    data class MarkAllPlayed(val podcastId: Long) : SubscriptionsIntent
    data class ToggleAutoDownload(val podcastId: Long) : SubscriptionsIntent
    data class RequestUnsubscribe(val podcastId: Long) : SubscriptionsIntent
    /** Unsubscribes for real; [RequestUnsubscribe] is the one that asks first. */
    data object ConfirmUnsubscribe : SubscriptionsIntent
    data object DismissUnsubscribe : SubscriptionsIntent
    data object Refresh : SubscriptionsIntent
}

sealed interface SubscriptionsEvent {
    data object SyncStarted : SubscriptionsEvent
    data class Message(val text: String) : SubscriptionsEvent
}

@HiltViewModel
class SubscriptionsViewModel @Inject constructor(
    private val podcastDao: PodcastDao,
    episodeDao: EpisodeDao,
    queue: PlayQueue,
    private val sync: SyncController,
    private val preferences: LibraryPreferences,
    private val subscriptions: SubscriptionRepository,
    private val actions: EpisodeActions,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val unsubscribeRequest = MutableStateFlow<Long?>(null)

    /** The choices that shape the list, bundled so [uiState] stays within combine's arity. */
    private data class View(
        val layout: LibraryLayout,
        val sort: LibrarySort,
        val unplayedOnly: Boolean,
        val query: String,
        val unsubscribeRequest: Long?,
    )

    private val _events = Channel<SubscriptionsEvent>(Channel.BUFFERED)
    val events: Flow<SubscriptionsEvent> = _events.receiveAsFlow()

    val uiState: StateFlow<SubscriptionsUiState> = combine(
        podcastDao.observeAll(),
        episodeDao.observeStats(),
        combine(preferences.layout, preferences.sort, preferences.unplayedOnly, query, unsubscribeRequest, ::View),
        queue.items,
        sync.syncing,
    ) { podcasts, stats, view, queued, syncing ->
        val unplayed = stats.associateBy(PodcastEpisodeStats::podcastId)
        val needle = view.query.trim()
        val all = podcasts.map { it.toItem(unplayed[it.id]?.unplayed ?: 0) to unplayed[it.id]?.latestPublishedAtMillis }
        val items = all
            .filter { (item, _) -> !view.unplayedOnly || item.unplayedCount > 0 }
            .filter { (item, _) -> needle.isEmpty() || item.matches(needle) }
        SubscriptionsUiState(
            podcasts = items.sortedWith(comparatorFor(view.sort)).map { it.first },
            layout = view.layout,
            sort = view.sort,
            unplayedOnly = view.unplayedOnly,
            query = view.query,
            confirmingUnsubscribe = all.map { it.first }.firstOrNull { it.id == view.unsubscribeRequest },
            isRefreshing = syncing,
            queueSize = queued.size,
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SubscriptionsUiState())

    fun onIntent(intent: SubscriptionsIntent) {
        when (intent) {
            is SubscriptionsIntent.SetLayout -> preferences.setLayout(intent.layout)
            is SubscriptionsIntent.SetSort -> preferences.setSort(intent.sort)
            is SubscriptionsIntent.SetUnplayedOnly -> preferences.setUnplayedOnly(intent.enabled)
            is SubscriptionsIntent.SetQuery -> query.value = intent.query
            is SubscriptionsIntent.MarkAllPlayed -> viewModelScope.launch {
                val count = actions.markAllPlayed(intent.podcastId)
                message("Marked $count ${if (count == 1) "episode" else "episodes"} played")
            }
            is SubscriptionsIntent.ToggleAutoDownload -> viewModelScope.launch {
                podcastDao.findById(intent.podcastId)?.let { podcastDao.update(it.copy(autoDownload = !it.autoDownload)) }
            }
            is SubscriptionsIntent.RequestUnsubscribe -> unsubscribeRequest.value = intent.podcastId
            SubscriptionsIntent.DismissUnsubscribe -> unsubscribeRequest.value = null
            SubscriptionsIntent.ConfirmUnsubscribe -> viewModelScope.launch {
                val podcast = unsubscribeRequest.getAndUpdate { null }?.let { podcastDao.findById(it) } ?: return@launch
                subscriptions.unsubscribe(podcast.feedUrl)
                message("Unsubscribed from ${podcast.title}")
            }
            SubscriptionsIntent.Refresh -> {
                sync.syncNow()
                _events.trySend(SubscriptionsEvent.SyncStarted)
            }
        }
    }

    private fun message(text: String) {
        _events.trySend(SubscriptionsEvent.Message(text))
    }

    private fun PodcastItem.matches(needle: String) =
        title.contains(needle, ignoreCase = true) || author?.contains(needle, ignoreCase = true) == true

    private fun PodcastEntity.toItem(unplayed: Int) =
        PodcastItem(id, feedUrl, title, author, imageUrl, unplayed, autoDownload)

    private fun comparatorFor(sort: LibrarySort): Comparator<Pair<PodcastItem, Long?>> {
        val byTitle = compareBy<Pair<PodcastItem, Long?>, String>(String.CASE_INSENSITIVE_ORDER) { it.first.title }
        return when (sort) {
            LibrarySort.TITLE -> byTitle
            LibrarySort.RECENT -> compareByDescending<Pair<PodcastItem, Long?>> { it.second ?: Long.MIN_VALUE }.then(byTitle)
            LibrarySort.UNPLAYED -> compareByDescending<Pair<PodcastItem, Long?>> { it.first.unplayedCount }.then(byTitle)
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
