// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.sonora.core.database.PodcastDao
import org.sonora.core.database.PodcastRepository
import org.sonora.core.database.SubscriptionRepository
import org.sonora.core.model.DirectoryPodcast
import org.sonora.core.network.isTransportAllowed
import org.sonora.core.sync.FeedFetcher
import org.sonora.core.work.SyncController
import org.sonora.feature.settings.SettingsStore

data class DiscoverResult(val podcast: DirectoryPodcast, val subscribed: Boolean)

data class AddUrlState(val url: String = "", val error: String? = null, val isSubmitting: Boolean = false)

sealed interface OpmlStatus {
    data object Idle : OpmlStatus
    data object Working : OpmlStatus
    data class Imported(val result: OpmlImportResult) : OpmlStatus
    data class Exported(val count: Int) : OpmlStatus
    data class Failed(val message: String) : OpmlStatus
}

data class DiscoverUiState(
    val query: String = "",
    val directory: DirectorySource = DirectorySource.APPLE,
    val results: List<DiscoverResult> = emptyList(),
    /** Shown while nothing is typed; independent of [results]. */
    val suggestions: List<DiscoverResult> = emptyList(),
    val isSearching: Boolean = false,
    val error: String? = null,
    val addUrl: AddUrlState? = null,
    val opml: OpmlStatus = OpmlStatus.Idle,
)

sealed interface DiscoverIntent {
    data class QueryChanged(val query: String) : DiscoverIntent
    data class SelectDirectory(val source: DirectorySource) : DiscoverIntent
    data class Subscribe(val podcast: DirectoryPodcast) : DiscoverIntent
    data object ShowAddUrl : DiscoverIntent
    data object DismissAddUrl : DiscoverIntent
    data class AddUrlChanged(val url: String) : DiscoverIntent
    data object SubmitUrl : DiscoverIntent
    /** The stream is consumed (and closed) by the import. */
    data class ImportOpml(val input: InputStream) : DiscoverIntent
    data class ExportOpml(val output: OutputStream) : DiscoverIntent
    data object DismissOpmlStatus : DiscoverIntent
}

sealed interface DiscoverEvent {
    data class Message(val text: String) : DiscoverEvent
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val search: DirectorySearch,
    private val suggestionSource: DirectorySuggestions,
    podcastDao: PodcastDao,
    private val subscriptions: SubscriptionRepository,
    private val podcastRepository: PodcastRepository,
    private val feedFetcher: FeedFetcher,
    private val opml: OpmlTransfer,
    private val sync: SyncController,
    private val settings: SettingsStore,
) : ViewModel() {
    private sealed interface SearchUpdate {
        data object Cleared : SearchUpdate
        data object Searching : SearchUpdate
        data class Found(val podcasts: List<DirectoryPodcast>) : SearchUpdate
        data class Failed(val message: String) : SearchUpdate
    }

    private val _state = MutableStateFlow(
        DiscoverUiState(suggestions = suggestionSource.cached().map { DiscoverResult(it, subscribed = false) }),
    )
    val uiState: StateFlow<DiscoverUiState> = _state.asStateFlow()

    private val _events = Channel<DiscoverEvent>(Channel.BUFFERED)
    val events: Flow<DiscoverEvent> = _events.receiveAsFlow()

    private val query = MutableStateFlow("")
    private val directory = MutableStateFlow(DirectorySource.APPLE)
    private var found = emptyList<DirectoryPodcast>()
    private var suggested = suggestionSource.cached()
    private var subscribedUrls = emptySet<String>()

    init {
        viewModelScope.launch {
            podcastDao.observeAll().map { all -> all.mapTo(HashSet()) { it.feedUrl.normalized() } }.collect {
                subscribedUrls = it
                publishResults()
            }
        }
        viewModelScope.launch {
            // Suggestions are a nicety: offline or a directory outage just leaves the screen as it was.
            try {
                suggested = suggestionSource.suggestions()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // keep whatever was cached
            }
            publishResults()
        }
        viewModelScope.launch {
            combine(query.debounce(SEARCH_DEBOUNCE_MS).map { it.trim() }.distinctUntilChanged(), directory, ::Pair)
                .flatMapLatest { (q, source) -> searchUpdates(q, source) }
                .collect(::apply)
        }
    }

    fun onIntent(intent: DiscoverIntent) {
        when (intent) {
            is DiscoverIntent.QueryChanged -> {
                query.value = intent.query
                _state.update { it.copy(query = intent.query) }
            }
            is DiscoverIntent.SelectDirectory -> {
                directory.value = intent.source
                _state.update { it.copy(directory = intent.source) }
            }
            is DiscoverIntent.Subscribe -> viewModelScope.launch { subscribeFromDirectory(intent.podcast) }
            DiscoverIntent.ShowAddUrl -> _state.update { it.copy(addUrl = AddUrlState()) }
            DiscoverIntent.DismissAddUrl -> _state.update { it.copy(addUrl = null) }
            is DiscoverIntent.AddUrlChanged -> _state.update { it.copy(addUrl = AddUrlState(url = intent.url)) }
            DiscoverIntent.SubmitUrl -> viewModelScope.launch { submitUrl() }
            is DiscoverIntent.ImportOpml -> viewModelScope.launch { importOpml(intent.input) }
            is DiscoverIntent.ExportOpml -> viewModelScope.launch { exportOpml(intent.output) }
            DiscoverIntent.DismissOpmlStatus -> _state.update { it.copy(opml = OpmlStatus.Idle) }
        }
    }

    // --- search ------------------------------------------------------------------------------

    private fun searchUpdates(query: String, source: DirectorySource): Flow<SearchUpdate> =
        if (query.isEmpty()) {
            flowOf(SearchUpdate.Cleared)
        } else {
            search.search(query, source)
                .map<List<DirectoryPodcast>, SearchUpdate> { SearchUpdate.Found(it) }
                .onStart { emit(SearchUpdate.Searching) }
                .catch { e ->
                    if (e is CancellationException) throw e
                    emit(SearchUpdate.Failed(e.message ?: "Search failed"))
                }
        }

    private fun apply(update: SearchUpdate) {
        when (update) {
            SearchUpdate.Cleared -> {
                found = emptyList()
                _state.update { it.copy(isSearching = false, error = null) }
            }
            SearchUpdate.Searching -> _state.update { it.copy(isSearching = true, error = null) }
            is SearchUpdate.Found -> {
                found = update.podcasts
                _state.update { it.copy(isSearching = false, error = null) }
            }
            is SearchUpdate.Failed -> {
                found = emptyList()
                _state.update { it.copy(isSearching = false, error = update.message) }
            }
        }
        publishResults()
    }

    private fun publishResults() {
        fun flagged(podcasts: List<DirectoryPodcast>) =
            podcasts.map { DiscoverResult(it, it.feedUrl.normalized() in subscribedUrls) }
        _state.update { it.copy(results = flagged(found), suggestions = flagged(suggested)) }
    }

    // --- subscribing -------------------------------------------------------------------------

    /** Subscribes straight away; the feed is loaded best-effort so offline taps still stick. */
    private suspend fun subscribeFromDirectory(podcast: DirectoryPodcast) {
        // Directory data is third-party input: apply the same transport gate as the add-by-URL dialog.
        if (podcast.feedUrl.toHttpUrlOrNull() == null || !isTransportAllowed(podcast.feedUrl, settings.allowCleartext)) {
            return message("${podcast.title} isn't available over HTTPS")
        }
        subscriptions.subscribe(podcast.feedUrl, podcast.title)
        try {
            val feed = feedFetcher.fetch(podcast.feedUrl)
            podcastRepository.refresh(podcast.feedUrl, feed)
            message("Subscribed to ${feed.title}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            sync.syncNow()
            message("Subscribed to ${podcast.title}. Episodes will load on the next sync.")
        }
    }

    private suspend fun submitUrl() {
        val dialog = _state.value.addUrl ?: return
        val raw = dialog.url.trim()
        val url = if (raw.contains("://")) raw else "https://$raw"
        val error = when {
            raw.isEmpty() -> "Enter a feed URL"
            url.toHttpUrlOrNull() == null -> "That doesn't look like a web address"
            !isTransportAllowed(url, settings.allowCleartext) ->
                "Only HTTPS feeds are allowed. Cleartext can be enabled in Settings for local testing."
            else -> null
        }
        if (error != null) return setDialog(dialog.copy(error = error))

        setDialog(dialog.copy(error = null, isSubmitting = true))
        try {
            val feed = feedFetcher.fetch(url)
            subscriptions.subscribe(url, feed.title)
            podcastRepository.refresh(url, feed)
            _state.update { it.copy(addUrl = null) }
            message("Subscribed to ${feed.title}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setDialog(dialog.copy(error = "Couldn't load feed: ${e.message ?: "unknown error"}", isSubmitting = false))
        }
    }

    private fun setDialog(dialog: AddUrlState) = _state.update { it.copy(addUrl = dialog) }

    // --- OPML --------------------------------------------------------------------------------

    private suspend fun importOpml(input: InputStream) {
        _state.update { it.copy(opml = OpmlStatus.Working) }
        try {
            val result = opml.import(input)
            if (result.imported > 0) sync.syncNow()
            _state.update { it.copy(opml = OpmlStatus.Imported(result)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(opml = OpmlStatus.Failed(e.message ?: "Couldn't read that file")) }
        }
    }

    private suspend fun exportOpml(output: OutputStream) {
        _state.update { it.copy(opml = OpmlStatus.Working) }
        try {
            val count = output.use { opml.export(it) }
            _state.update { it.copy(opml = OpmlStatus.Exported(count)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(opml = OpmlStatus.Failed(e.message ?: "Couldn't write the file")) }
        }
    }

    private fun message(text: String) {
        _events.trySend(DiscoverEvent.Message(text))
    }

    private fun String.normalized() = trim().trimEnd('/').lowercase()

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}
