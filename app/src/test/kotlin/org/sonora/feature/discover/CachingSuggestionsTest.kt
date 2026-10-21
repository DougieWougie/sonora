// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.discover

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.sonora.core.database.MutableClock
import org.sonora.core.model.DirectoryPodcast
import org.sonora.core.network.discovery.DirectoryException

class CachingSuggestionsTest {
    private fun pod(title: String) = DirectoryPodcast(title, null, "https://f/$title", null)

    private class FakeStore(var saved: CachedSuggestions? = null) : SuggestionStore {
        override fun load() = saved
        override fun save(suggestions: CachedSuggestions) { saved = suggestions }
    }

    private class FakeSource(var results: List<DirectoryPodcast>, var failure: Exception? = null, val latency: Long = 0) :
        DirectorySuggestions {
        var calls = 0
        override suspend fun suggestions(): List<DirectoryPodcast> {
            calls++
            delay(latency)
            failure?.let { throw it }
            return results
        }
    }

    private val clock = MutableClock()
    private val store = FakeStore()
    private fun caching(source: DirectorySuggestions) = CachingSuggestions(source, store, clock, ttlMillis = 1000)

    @Test
    fun `nothing is cached until the first fetch`() {
        assertEquals(emptyList<DirectoryPodcast>(), caching(FakeSource(emptyList())).cached())
    }

    @Test
    fun `the first fetch goes to the source and is saved`() = runTest {
        val source = FakeSource(listOf(pod("A")))

        assertEquals(listOf(pod("A")), caching(source).suggestions())

        assertEquals(CachedSuggestions(clock.nowMillis, listOf(pod("A"))), store.saved)
    }

    @Test
    fun `a fresh cache is served without asking the source`() = runTest {
        store.saved = CachedSuggestions(clock.nowMillis - 999, listOf(pod("Cached")))
        val source = FakeSource(listOf(pod("New")))

        assertEquals(listOf(pod("Cached")), caching(source).suggestions())
        assertEquals(0, source.calls)
    }

    @Test
    fun `a stale cache is still available instantly but is refreshed by suggestions`() = runTest {
        store.saved = CachedSuggestions(clock.nowMillis - 1000, listOf(pod("Old")))
        val source = FakeSource(listOf(pod("New")))
        val caching = caching(source)

        assertEquals(listOf(pod("Old")), caching.cached())
        assertEquals(listOf(pod("New")), caching.suggestions())
        assertEquals(listOf(pod("New")), caching.cached())
    }

    @Test
    fun `a failed refresh falls back to the stale cache`() = runTest {
        store.saved = CachedSuggestions(clock.nowMillis - 5000, listOf(pod("Old")))
        val source = FakeSource(emptyList(), failure = DirectoryException("offline"))

        assertEquals(listOf(pod("Old")), caching(source).suggestions())
        assertEquals(clock.nowMillis - 5000, store.saved?.fetchedAtMillis)
    }

    @Test
    fun `a failure with nothing cached is thrown`() = runTest {
        val source = FakeSource(emptyList(), failure = DirectoryException("offline"))

        assertThrows<DirectoryException> { caching(source).suggestions() }
        assertNull(store.saved)
    }

    @Test
    fun `cancellation is not mistaken for a failed refresh`() = runTest {
        store.saved = CachedSuggestions(clock.nowMillis - 5000, listOf(pod("Old")))
        val source = FakeSource(emptyList(), failure = kotlinx.coroutines.CancellationException("stop"))

        assertThrows<kotlinx.coroutines.CancellationException> { caching(source).suggestions() }
    }

    @Test
    fun `an empty answer is not cached`() = runTest {
        caching(FakeSource(emptyList())).suggestions()
        assertNull(store.saved)
    }

    @Test
    fun `callers arriving together share one fetch`() = runTest {
        val source = FakeSource(listOf(pod("A")), latency = 500)
        val caching = caching(source)

        val results = listOf(async { caching.suggestions() }, async { caching.suggestions() }).map { it.await() }

        assertEquals(listOf(listOf(pod("A")), listOf(pod("A"))), results)
        assertEquals(1, source.calls)
    }
}
