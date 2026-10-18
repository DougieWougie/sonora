// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
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
import org.sonora.core.media.PlayQueue
import org.sonora.core.work.SyncController

enum class LibraryLayout { GRID, LIST }
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
    val isRefreshing: Boolean = false,
    val queueSize: Int = 0,
    val isLoading: Boolean = true,
)

sealed interface SubscriptionsIntent {
    data class SetLayout(val layout: LibraryLayout) : SubscriptionsIntent
    data class SetSort(val sort: LibrarySort) : SubscriptionsIntent
    data class SetUnplayedOnly(val enabled: Boolean) : SubscriptionsIntent
    data object Refresh : SubscriptionsIntent
}

sealed interface SubscriptionsEvent {
    data object SyncStarted : SubscriptionsEvent
}

@HiltViewModel
class SubscriptionsViewModel @Inject constructor(
    podcastDao: PodcastDao,
    episodeDao: EpisodeDao,
    queue: PlayQueue,
    private val sync: SyncController,
    private val preferences: LibraryPreferences,
) : ViewModel() {
    private val _events = Channel<SubscriptionsEvent>(Channel.BUFFERED)
    val events: Flow<SubscriptionsEvent> = _events.receiveAsFlow()

    val uiState: StateFlow<SubscriptionsUiState> = combine(
        podcastDao.observeAll(),
        episodeDao.observeStats(),
        combine(preferences.layout, preferences.sort, preferences.unplayedOnly, ::Triple),
        queue.items,
        sync.syncing,
    ) { podcasts, stats, (layout, sort, unplayedOnly), queued, syncing ->
        val unplayed = stats.associateBy(PodcastEpisodeStats::podcastId)
        val items = podcasts.map { it.toItem(unplayed[it.id]?.unplayed ?: 0) to unplayed[it.id]?.latestPublishedAtMillis }
            .filter { (item, _) -> !unplayedOnly || item.unplayedCount > 0 }
        SubscriptionsUiState(
            podcasts = items.sortedWith(comparatorFor(sort)).map { it.first },
            layout = layout,
            sort = sort,
            unplayedOnly = unplayedOnly,
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
            SubscriptionsIntent.Refresh -> {
                sync.syncNow()
                _events.trySend(SubscriptionsEvent.SyncStarted)
            }
        }
    }

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
