// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.discover

import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import app.cash.turbine.test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.sonora.core.database.FakeEpisodeDao
import org.sonora.core.database.FakePodcastDao
import org.sonora.core.database.FakeSubscriptionChangeDao
import org.sonora.core.database.FakeTransactionRunner
import org.sonora.core.database.MutableClock
import org.sonora.core.database.PodcastEntity
import org.sonora.core.database.PodcastRepository
import org.sonora.core.database.SubscriptionRepository
import org.sonora.core.model.DirectoryPodcast
import org.sonora.core.model.ParsedEpisode
import org.sonora.core.model.ParsedFeed
import org.sonora.core.network.discovery.DirectoryException
import org.sonora.core.sync.FeedFetcher
import org.sonora.testutil.FakeDirectorySearch
import org.sonora.testutil.FakeDirectorySuggestions
import org.sonora.testutil.FakeOpmlTransfer
import org.sonora.testutil.FakeSettingsStore
import org.sonora.testutil.FakeSyncController
import org.sonora.testutil.MainDispatcherExtension

class DiscoverViewModelTest {
    @JvmField @RegisterExtension
    val main = MainDispatcherExtension()

    private val search = FakeDirectorySearch()
    private val suggestions = FakeDirectorySuggestions()
    private val podcasts = FakePodcastDao()
    private val episodes = FakeEpisodeDao()
    private val changes = FakeSubscriptionChangeDao()
    private val clock = MutableClock()
    private val subscriptions = SubscriptionRepository(podcasts, changes, FakeTransactionRunner(), clock)
    private val repository = PodcastRepository(podcasts, episodes, FakeTransactionRunner(), clock)
    private val opml = FakeOpmlTransfer()
    private val sync = FakeSyncController()
    private val settings = FakeSettingsStore()
    private var feedResult: () -> ParsedFeed = {
        ParsedFeed("Fetched Title", null, null, null, "https://img/a.png", listOf(ParsedEpisode("e1", "One", null, "https://cdn/1.mp3", null, null, null, 1, null)))
    }
    private val fetchedUrls = mutableListOf<String>()
    private val fetcher = FeedFetcher { url -> fetchedUrls += url; feedResult() }

    private fun vm() = DiscoverViewModel(search, suggestions, podcasts, subscriptions, repository, fetcher, opml, sync, settings)

    private fun pod(title: String, feed: String = "https://f/$title") = DirectoryPodcast(title, "auth", feed, "https://art/$title")

    // --- suggestions -------------------------------------------------------------------------

    @Test
    fun `suggestions are loaded on start and flagged when already subscribed`() = runTest {
        podcasts.insert(PodcastEntity(feedUrl = "https://f/Known", title = "Known"))
        suggestions.results = listOf(pod("Known"), pod("New"))

        val vm = vm()
        runCurrent()

        assertEquals(listOf("Known", "New"), vm.uiState.value.suggestions.map { it.podcast.title })
        assertEquals(listOf(true, false), vm.uiState.value.suggestions.map { it.subscribed })
    }

    @Test
    fun `cached suggestions show immediately, before the refresh answers`() = runTest {
        suggestions.cached = listOf(pod("Cached"))
        suggestions.results = listOf(pod("Fresh"))

        val vm = vm()
        assertEquals(listOf("Cached"), vm.uiState.value.suggestions.map { it.podcast.title })

        runCurrent()
        assertEquals(listOf("Fresh"), vm.uiState.value.suggestions.map { it.podcast.title })
    }

    @Test
    fun `a failed refresh keeps the cached suggestions`() = runTest {
        suggestions.cached = listOf(pod("Cached"))
        suggestions.failure = DirectoryException("offline")

        val vm = vm()
        runCurrent()

        assertEquals(listOf("Cached"), vm.uiState.value.suggestions.map { it.podcast.title })
    }

    @Test
    fun `subscribing to a suggestion marks it added`() = runTest {
        suggestions.results = listOf(pod("New"))
        val vm = vm()
        runCurrent()

        vm.onIntent(DiscoverIntent.Subscribe(pod("New")))
        advanceUntilIdle()

        assertTrue(vm.uiState.value.suggestions.single().subscribed)
    }

    @Test
    fun `suggestions failing to load leaves discover usable without an error`() = runTest {
        suggestions.failure = DirectoryException("offline")

        val vm = vm()
        runCurrent()

        assertTrue(vm.uiState.value.suggestions.isEmpty())
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `searching does not touch the suggestions`() = runTest {
        suggestions.results = listOf(pod("Hot"))
        search.results = { _, _ -> listOf(pod("Found")) }
        val vm = vm()
        vm.onIntent(DiscoverIntent.QueryChanged("x"))
        advanceTimeBy(300); runCurrent()

        assertEquals(listOf("Found"), vm.uiState.value.results.map { it.podcast.title })
        assertEquals(listOf("Hot"), vm.uiState.value.suggestions.map { it.podcast.title })
        assertEquals(1, suggestions.calls)
    }

    // --- search ------------------------------------------------------------------------------

    @Test
    fun `typing is debounced by 300ms and only the final query is searched`() = runTest {
        val vm = vm()
        vm.onIntent(DiscoverIntent.QueryChanged("a"))
        advanceTimeBy(100)
        vm.onIntent(DiscoverIntent.QueryChanged("ab"))
        advanceTimeBy(100)
        vm.onIntent(DiscoverIntent.QueryChanged("abc"))
        advanceTimeBy(299)
        runCurrent()
        assertTrue(search.calls.isEmpty())
        assertEquals("abc", vm.uiState.value.query)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("abc" to DirectorySource.APPLE), search.calls)
    }

    @Test
    fun `shows results and flags the ones already subscribed`() = runTest {
        podcasts.insert(PodcastEntity(feedUrl = "https://f/Known", title = "Known"))
        search.results = { _, _ -> listOf(pod("Known"), pod("New")) }
        val vm = vm()

        vm.onIntent(DiscoverIntent.QueryChanged("x"))
        advanceTimeBy(300); runCurrent()

        val results = vm.uiState.value.results
        assertEquals(listOf("Known", "New"), results.map { it.podcast.title })
        assertEquals(listOf(true, false), results.map { it.subscribed })
        assertFalse(vm.uiState.value.isSearching)
    }

    @Test
    fun `clearing the query clears results without searching`() = runTest {
        search.results = { _, _ -> listOf(pod("A")) }
        val vm = vm()
        vm.onIntent(DiscoverIntent.QueryChanged("x"))
        advanceTimeBy(300); runCurrent()
        assertEquals(1, vm.uiState.value.results.size)

        vm.onIntent(DiscoverIntent.QueryChanged("   "))
        advanceTimeBy(300); runCurrent()

        assertTrue(vm.uiState.value.results.isEmpty())
        assertEquals(1, search.calls.size)
    }

    @Test
    fun `switching directory repeats the search against the other directory`() = runTest {
        search.results = { _, source -> listOf(pod(source.name)) }
        val vm = vm()
        vm.onIntent(DiscoverIntent.QueryChanged("x"))
        advanceTimeBy(300); runCurrent()

        vm.onIntent(DiscoverIntent.SelectDirectory(DirectorySource.PODCAST_INDEX))
        runCurrent()

        assertEquals(DirectorySource.PODCAST_INDEX, vm.uiState.value.directory)
        assertEquals(listOf("x" to DirectorySource.APPLE, "x" to DirectorySource.PODCAST_INDEX), search.calls)
        assertEquals(listOf("PODCAST_INDEX"), vm.uiState.value.results.map { it.podcast.title })
    }

    @Test
    fun `a failed search shows an error and a later success clears it`() = runTest {
        search.failure = DirectoryException("Directory returned HTTP 500")
        val vm = vm()
        vm.onIntent(DiscoverIntent.QueryChanged("x"))
        advanceTimeBy(300); runCurrent()

        assertEquals("Directory returned HTTP 500", vm.uiState.value.error)
        assertFalse(vm.uiState.value.isSearching)

        search.failure = null
        vm.onIntent(DiscoverIntent.QueryChanged("xy"))
        advanceTimeBy(300); runCurrent()
        assertNull(vm.uiState.value.error)
    }

    // --- subscribing -------------------------------------------------------------------------

    @Test
    fun `subscribing from a result subscribes, loads the feed and marks the result subscribed`() = runTest {
        search.results = { _, _ -> listOf(pod("New", "https://f/new")) }
        val vm = vm()
        vm.onIntent(DiscoverIntent.QueryChanged("x"))
        advanceTimeBy(300); runCurrent()

        vm.onIntent(DiscoverIntent.Subscribe(vm.uiState.value.results.single().podcast))
        advanceUntilIdle()

        assertTrue(changes.rows.getValue("https://f/new").subscribed)
        assertEquals(listOf("https://f/new"), fetchedUrls)
        assertEquals("Fetched Title", podcasts.findByFeedUrl("https://f/new")?.title)
        assertEquals(1, episodes.rows.value.size)
        assertTrue(vm.uiState.value.results.single().subscribed)
    }

    @Test
    fun `a feed that cannot be fetched is still subscribed and left to the next sync`() = runTest {
        feedResult = { throw IOException("offline") }
        val vm = vm()
        vm.events.test {
            vm.onIntent(DiscoverIntent.Subscribe(pod("Offline", "https://f/off")))
            val message = awaitItem() as DiscoverEvent.Message
            assertTrue("Offline" in message.text)
        }
        assertNotNull(podcasts.findByFeedUrl("https://f/off"))
        assertEquals(1, sync.requests)
    }

    @Test
    fun `a directory result with a cleartext feed is not subscribed unless cleartext is allowed`() = runTest {
        val vm = vm()
        vm.events.test {
            vm.onIntent(DiscoverIntent.Subscribe(pod("Plain", "http://f/plain")))
            val message = awaitItem() as DiscoverEvent.Message
            assertTrue("HTTPS" in message.text)
        }
        assertNull(podcasts.findByFeedUrl("http://f/plain"))
        assertTrue(changes.rows.isEmpty())
        assertTrue(fetchedUrls.isEmpty())

        settings.allowCleartext = true
        vm.onIntent(DiscoverIntent.Subscribe(pod("Plain", "http://f/plain")))
        advanceUntilIdle()
        assertNotNull(podcasts.findByFeedUrl("http://f/plain"))
    }

    // --- add by URL --------------------------------------------------------------------------

    @Test
    fun `the add-by-url dialog opens and closes`() = runTest {
        val vm = vm()
        vm.onIntent(DiscoverIntent.ShowAddUrl)
        assertNotNull(vm.uiState.value.addUrl)
        vm.onIntent(DiscoverIntent.DismissAddUrl)
        assertNull(vm.uiState.value.addUrl)
    }

    @Test
    fun `an empty or malformed url is rejected without touching the network`() = runTest {
        val vm = vm()
        vm.onIntent(DiscoverIntent.ShowAddUrl)
        vm.onIntent(DiscoverIntent.SubmitUrl)
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.addUrl?.error)

        vm.onIntent(DiscoverIntent.AddUrlChanged("not a url"))
        vm.onIntent(DiscoverIntent.SubmitUrl)
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.addUrl?.error)
        assertTrue(fetchedUrls.isEmpty())
    }

    @Test
    fun `a cleartext url is rejected unless the user allowed cleartext`() = runTest {
        val vm = vm()
        vm.onIntent(DiscoverIntent.ShowAddUrl)
        vm.onIntent(DiscoverIntent.AddUrlChanged("http://example.com/feed.xml"))
        vm.onIntent(DiscoverIntent.SubmitUrl)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.addUrl!!.error!!.contains("HTTPS"))
        assertTrue(fetchedUrls.isEmpty())

        settings.allowCleartext = true
        vm.onIntent(DiscoverIntent.SubmitUrl)
        advanceUntilIdle()
        assertEquals(listOf("http://example.com/feed.xml"), fetchedUrls)
    }

    @Test
    fun `a bare host gets https`() = runTest {
        val vm = vm()
        vm.onIntent(DiscoverIntent.ShowAddUrl)
        vm.onIntent(DiscoverIntent.AddUrlChanged("  example.com/feed.xml "))
        vm.onIntent(DiscoverIntent.SubmitUrl)
        advanceUntilIdle()
        assertEquals(listOf("https://example.com/feed.xml"), fetchedUrls)
    }

    @Test
    fun `a url that is not a feed keeps the dialog open with the error`() = runTest {
        feedResult = { throw IOException("Not a podcast feed") }
        val vm = vm()
        vm.onIntent(DiscoverIntent.ShowAddUrl)
        vm.onIntent(DiscoverIntent.AddUrlChanged("https://example.com/page"))
        vm.onIntent(DiscoverIntent.SubmitUrl)
        advanceUntilIdle()

        val dialog = vm.uiState.value.addUrl!!
        assertTrue(dialog.error!!.contains("Not a podcast feed"))
        assertFalse(dialog.isSubmitting)
        assertNull(podcasts.findByFeedUrl("https://example.com/page"))
    }

    @Test
    fun `a valid feed url subscribes with the real title and closes the dialog`() = runTest {
        val vm = vm()
        vm.events.test {
            vm.onIntent(DiscoverIntent.ShowAddUrl)
            vm.onIntent(DiscoverIntent.AddUrlChanged("https://example.com/feed.xml"))
            vm.onIntent(DiscoverIntent.SubmitUrl)
            val message = awaitItem() as DiscoverEvent.Message
            assertTrue("Fetched Title" in message.text)
        }
        assertNull(vm.uiState.value.addUrl)
        assertEquals("Fetched Title", podcasts.findByFeedUrl("https://example.com/feed.xml")?.title)
        assertTrue(changes.rows.getValue("https://example.com/feed.xml").subscribed)
    }

    // --- OPML --------------------------------------------------------------------------------

    @Test
    fun `importing opml reports the result and syncs the new feeds`() = runTest {
        opml.importResult = OpmlImportResult(imported = 3, alreadySubscribed = 1)
        val vm = vm()

        vm.onIntent(DiscoverIntent.ImportOpml(ByteArrayInputStream(ByteArray(0))))
        advanceUntilIdle()

        assertEquals(OpmlStatus.Imported(OpmlImportResult(3, 1)), vm.uiState.value.opml)
        assertEquals(1, sync.requests)
    }

    @Test
    fun `importing nothing new does not sync`() = runTest {
        opml.importResult = OpmlImportResult(imported = 0, alreadySubscribed = 2)
        val vm = vm()
        vm.onIntent(DiscoverIntent.ImportOpml(ByteArrayInputStream(ByteArray(0))))
        advanceUntilIdle()
        assertEquals(0, sync.requests)
    }

    @Test
    fun `a broken opml file is reported as a failure`() = runTest {
        opml.failure = IOException("bad xml")
        val vm = vm()
        vm.onIntent(DiscoverIntent.ImportOpml(ByteArrayInputStream(ByteArray(0))))
        advanceUntilIdle()
        assertEquals(OpmlStatus.Failed("bad xml"), vm.uiState.value.opml)
    }

    @Test
    fun `exporting opml reports how many subscriptions were written`() = runTest {
        opml.exported = 7
        val vm = vm()
        vm.onIntent(DiscoverIntent.ExportOpml(java.io.ByteArrayOutputStream()))
        advanceUntilIdle()
        assertEquals(OpmlStatus.Exported(7), vm.uiState.value.opml)
        vm.onIntent(DiscoverIntent.DismissOpmlStatus)
        assertEquals(OpmlStatus.Idle, vm.uiState.value.opml)
    }

    // --- filters -----------------------------------------------------------------------------

    private fun tagged(title: String, category: String?, explicit: Boolean = false) =
        DirectoryPodcast(title, "auth", "https://f/$title", null, category, explicit)

    @Test
    fun `categories are the distinct ones among the suggestions, sorted`() = runTest {
        suggestions.results = listOf( tagged("A", "News"), tagged("B", "History"), tagged("C", "News"), tagged("D", null))

        val vm = vm()
        runCurrent()

        assertEquals(listOf("History", "News"), vm.uiState.value.categories)
    }

    @Test
    fun `selecting a category narrows suggestions and selecting it again clears the filter`() = runTest {
        suggestions.results = listOf( tagged("A", "News"), tagged("B", "History"))
        val vm = vm()
        runCurrent()

        vm.onIntent(DiscoverIntent.SelectCategory("News"))
        assertEquals("News", vm.uiState.value.category)
        assertEquals(listOf("A"), vm.uiState.value.suggestions.map { it.podcast.title })
        // Choices stay available so the user can switch without clearing first.
        assertEquals(listOf("History", "News"), vm.uiState.value.categories)

        vm.onIntent(DiscoverIntent.SelectCategory(null))
        assertEquals(listOf("A", "B"), vm.uiState.value.suggestions.map { it.podcast.title })
    }

    @Test
    fun `explicit shows are hidden by default, including cached suggestions shown before the refresh`() = runTest {
        suggestions.cached = listOf(tagged("CachedClean", null), tagged("CachedRude", null, explicit = true))
        suggestions.results = listOf(tagged("Clean", null), tagged("Rude", null, explicit = true))

        val vm = vm()
        assertTrue(vm.uiState.value.hideExplicit)
        assertEquals(listOf("CachedClean"), vm.uiState.value.suggestions.map { it.podcast.title })

        runCurrent()
        assertEquals(listOf("Clean"), vm.uiState.value.suggestions.map { it.podcast.title })
    }

    @Test
    fun `hide explicit removes explicit shows from suggestions and results`() = runTest {
        suggestions.results = listOf( tagged("Clean", null), tagged("Rude", null, explicit = true))
        search.results = { _, _ -> listOf( tagged("ClearHit", null), tagged("RudeHit", null, explicit = true)) }
        val vm = vm()
        vm.onIntent(DiscoverIntent.QueryChanged("hit"))
        advanceTimeBy(400)
        runCurrent()

        vm.onIntent(DiscoverIntent.SetHideExplicit(false))
        assertEquals(2, vm.uiState.value.results.size)
        vm.onIntent(DiscoverIntent.SetHideExplicit(true))

        assertTrue(vm.uiState.value.hideExplicit)
        assertEquals(listOf("Clean"), vm.uiState.value.suggestions.map { it.podcast.title })
        assertEquals(listOf("ClearHit"), vm.uiState.value.results.map { it.podcast.title })

        vm.onIntent(DiscoverIntent.SetHideExplicit(false))
        assertEquals(2, vm.uiState.value.results.size)
    }

    @Test
    fun `categories follow what is on screen - results while searching`() = runTest {
        suggestions.results = listOf( tagged("A", "News"))
        search.results = { _, _ -> listOf( tagged("Hit", "Comedy")) }
        val vm = vm()
        vm.onIntent(DiscoverIntent.QueryChanged("hit"))
        advanceTimeBy(400)
        runCurrent()

        assertEquals(listOf("Comedy"), vm.uiState.value.categories)
    }
}
