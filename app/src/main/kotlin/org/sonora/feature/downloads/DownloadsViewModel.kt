// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.downloads

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.sonora.core.database.EpisodeDao
import org.sonora.core.database.EpisodeEntity
import org.sonora.core.database.PodcastDao
import org.sonora.core.download.DownloadWaits
import org.sonora.core.download.EpisodeActions
import org.sonora.core.download.EvictionNotices
import org.sonora.core.download.WaitReason
import org.sonora.core.media.PlaybackPort
import org.sonora.core.model.DownloadState
import org.sonora.feature.common.EpisodeItem
import org.sonora.feature.common.toItem
import org.sonora.feature.settings.SettingsStore

enum class DownloadSort { NEWEST, LARGEST, SHOW }

/** A queued or running download. [waiting] says what it is held back by, when that is known. */
data class ActiveDownload(val item: EpisodeItem, val waiting: WaitReason?)

/** Sizes are the bytes recorded for completed downloads; [limitBytes] is 0 when there is no cap. */
data class StorageUsage(
    val totalBytes: Long = 0,
    val playedBytes: Long = 0,
    val playedCount: Int = 0,
    val limitBytes: Long = 0,
    val downloadedCount: Int = 0,
) {
    val unplayedBytes: Long get() = totalBytes - playedBytes
}

data class DownloadsUiState(
    val isLoading: Boolean = true,
    val usage: StorageUsage = StorageUsage(),
    val active: List<ActiveDownload> = emptyList(),
    val failed: List<EpisodeItem> = emptyList(),
    val downloaded: List<EpisodeItem> = emptyList(),
    val sort: DownloadSort = DownloadSort.NEWEST,
    /** Unplayed episodes retention removed since the user last dismissed the notice. */
    val evictedUnplayed: Int = 0,
) {
    val isEmpty: Boolean get() = active.isEmpty() && failed.isEmpty() && downloaded.isEmpty()
}

sealed interface DownloadsIntent {
    data class SetSort(val sort: DownloadSort) : DownloadsIntent
    data class Play(val episodeId: Long) : DownloadsIntent
    data class Delete(val episodeId: Long) : DownloadsIntent
    data class Cancel(val episodeId: Long) : DownloadsIntent
    data class Retry(val episodeId: Long) : DownloadsIntent
    data object DeleteAllPlayed : DownloadsIntent
    data object DismissEvictionNotice : DownloadsIntent
}

sealed interface DownloadsEvent {
    data class Message(val text: String) : DownloadsEvent
}

private fun storageUsage(episodes: List<EpisodeEntity>, limitBytes: Long): StorageUsage {
    val downloaded = episodes.filter { it.downloadState == DownloadState.DOWNLOADED }
    val played = downloaded.filter { it.played }
    return StorageUsage(
        totalBytes = downloaded.sumOf { it.downloadedBytes },
        playedBytes = played.sumOf { it.downloadedBytes },
        playedCount = played.size,
        limitBytes = limitBytes,
        downloadedCount = downloaded.size,
    )
}

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    episodeDao: EpisodeDao,
    podcastDao: PodcastDao,
    private val actions: EpisodeActions,
    private val playback: PlaybackPort,
    waits: DownloadWaits,
    private val settings: SettingsStore,
    private val notices: EvictionNotices,
) : ViewModel() {
    private val sort = MutableStateFlow(DownloadSort.NEWEST)

    private val _events = Channel<DownloadsEvent>(Channel.BUFFERED)
    val events: Flow<DownloadsEvent> = _events.receiveAsFlow()

    val uiState: StateFlow<DownloadsUiState> = combine(
        episodeDao.observeByDownloadStates(TRACKED_STATES),
        podcastDao.observeAll(),
        waits.observe(),
        sort,
        notices.unplayedRemoved,
    ) { episodes, podcasts, reasons, sort, evicted ->
        val byId = podcasts.associateBy { it.id }
        val items = episodes.map { it.toItem(byId[it.podcastId], queued = false) }
        val downloaded = items.filter { it.downloadState == DownloadState.DOWNLOADED }
        DownloadsUiState(
            isLoading = false,
            usage = storageUsage(episodes, settings.maxStorageBytes),
            active = items
                .filter { it.downloadState == DownloadState.QUEUED || it.downloadState == DownloadState.DOWNLOADING }
                .sortedWith(compareBy<EpisodeItem> { it.downloadState != DownloadState.DOWNLOADING }.then(NEWEST_FIRST))
                .map { ActiveDownload(it, reasons[it.id]) },
            failed = items.filter { it.downloadState == DownloadState.FAILED }.sortedWith(NEWEST_FIRST),
            downloaded = downloaded.sortedWith(sort.comparator),
            sort = sort,
            evictedUnplayed = evicted,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DownloadsUiState())

    fun onIntent(intent: DownloadsIntent) {
        when (intent) {
            is DownloadsIntent.SetSort -> sort.value = intent.sort
            is DownloadsIntent.Play -> attempt { playback.play(intent.episodeId) }
            is DownloadsIntent.Delete -> attempt { actions.deleteDownload(intent.episodeId) }
            is DownloadsIntent.Cancel -> attempt { actions.cancelDownload(intent.episodeId) }
            is DownloadsIntent.Retry -> attempt { actions.download(intent.episodeId) }
            DownloadsIntent.DismissEvictionNotice -> notices.dismiss()
            DownloadsIntent.DeleteAllPlayed -> attempt {
                val count = actions.deleteAllPlayedDownloads()
                _events.trySend(DownloadsEvent.Message("Deleted $count played download${if (count == 1) "" else "s"}"))
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
                _events.trySend(DownloadsEvent.Message(e.message ?: "Something went wrong"))
            }
        }
    }

    private val DownloadSort.comparator: Comparator<EpisodeItem>
        get() = when (this) {
            DownloadSort.NEWEST -> NEWEST_FIRST
            DownloadSort.LARGEST -> compareByDescending<EpisodeItem> { it.downloadedBytes }.then(NEWEST_FIRST)
            DownloadSort.SHOW -> compareBy<EpisodeItem, String>(String.CASE_INSENSITIVE_ORDER) { it.podcastTitle.orEmpty() }
                .then(NEWEST_FIRST)
        }

    private companion object {
        val TRACKED_STATES = listOf(DownloadState.QUEUED, DownloadState.DOWNLOADING, DownloadState.FAILED, DownloadState.DOWNLOADED)
        val NEWEST_FIRST = compareByDescending<EpisodeItem> { it.publishedAtMillis ?: Long.MIN_VALUE }
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** Usage of completed downloads, for the places that only need the headline figures. */
@HiltViewModel
class DownloadsSummaryViewModel @Inject constructor(
    episodeDao: EpisodeDao,
    private val settings: SettingsStore,
    notices: EvictionNotices,
) : ViewModel() {
    /** Unplayed episodes retention removed since the notice was last dismissed. */
    val evictedUnplayed: StateFlow<Int> = notices.unplayedRemoved

    val usage: StateFlow<StorageUsage> = episodeDao.observeByDownloadStates(listOf(DownloadState.DOWNLOADED))
        .map { storageUsage(it, settings.maxStorageBytes) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), StorageUsage())
}
