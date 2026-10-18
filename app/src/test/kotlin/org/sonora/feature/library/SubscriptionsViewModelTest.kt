// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.library

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.sonora.core.database.EpisodeEntity
import org.sonora.core.database.FakeEpisodeDao
import org.sonora.core.database.FakePodcastDao
import org.sonora.core.database.PodcastEntity
import org.sonora.core.media.PersistentPlayQueue
import org.sonora.core.media.FakeQueueStorage
import org.sonora.testutil.FakeLibraryPreferences
import org.sonora.testutil.FakeSyncController
import org.sonora.testutil.MainDispatcherExtension
import org.sonora.testutil.awaitUntil

class SubscriptionsViewModelTest {
    @JvmField @RegisterExtension
    val main = MainDispatcherExtension()

    private val podcasts = FakePodcastDao()
    private val episodes = FakeEpisodeDao()
    private val queue = PersistentPlayQueue(FakeQueueStorage())
    private val sync = FakeSyncController()

    private val preferences = FakeLibraryPreferences()

    private fun vm() = SubscriptionsViewModel(podcasts, episodes, queue, sync, preferences)

    private suspend fun podcast(title: String, autoDownload: Boolean = false): Long =
        podcasts.insert(PodcastEntity(feedUrl = "https://f/$title", title = title, author = "by $title", autoDownload = autoDownload))

    private suspend fun episode(podcastId: Long, published: Long, played: Boolean = false, guid: String = "$podcastId-$published") =
        episodes.insertAll(listOf(EpisodeEntity(podcastId = podcastId, guid = guid, title = guid, enclosureUrl = "https://e/$guid", publishedAtMillis = published, played = played)))

    @Test
    fun `starts in a loading state and then lists subscriptions alphabetically with unplayed counts`() = runTest {
        val b = podcast("beta")
        val a = podcast("Alpha")
        episode(a, 1); episode(a, 2); episode(a, 3, played = true); episode(b, 4)

        vm().uiState.test {
            assertTrue(awaitItem().isLoading)
            val state = awaitUntil { !it.isLoading }
            assertEquals(listOf("Alpha", "beta"), state.podcasts.map { it.title })
            assertEquals(listOf(2, 1), state.podcasts.map { it.unplayedCount })
            assertEquals(LibraryLayout.GRID, state.layout)
        }
    }

    @Test
    fun `a library with no subscriptions is empty, not loading`() = runTest {
        vm().uiState.test {
            val state = awaitUntil { !it.isLoading }
            assertTrue(state.podcasts.isEmpty())
        }
    }

    @Test
    fun `sorts by most recent episode`() = runTest {
        val old = podcast("Old"); val fresh = podcast("Fresh"); val none = podcast("None")
        episode(old, 100); episode(fresh, 900)

        val vm = vm()
        vm.uiState.test {
            awaitUntil { !it.isLoading }
            vm.onIntent(SubscriptionsIntent.SetSort(LibrarySort.RECENT))
            val state = awaitUntil { it.sort == LibrarySort.RECENT && it.podcasts.isNotEmpty() }
            assertEquals(listOf("Fresh", "Old", "None"), state.podcasts.map { it.title })
            assertEquals(none, state.podcasts.last().id)
        }
    }

    @Test
    fun `sorts by most unplayed episodes with ties broken by title`() = runTest {
        val a = podcast("A"); val b = podcast("B"); val c = podcast("C")
        episode(b, 1); episode(b, 2); episode(a, 3); episode(c, 4)

        val vm = vm()
        vm.uiState.test {
            awaitUntil { !it.isLoading }
            vm.onIntent(SubscriptionsIntent.SetSort(LibrarySort.UNPLAYED))
            val state = awaitUntil { it.sort == LibrarySort.UNPLAYED && it.podcasts.isNotEmpty() }
            assertEquals(listOf("B", "A", "C"), state.podcasts.map { it.title })
        }
    }

    @Test
    fun `can filter down to podcasts with unplayed episodes`() = runTest {
        val a = podcast("A"); val b = podcast("B")
        episode(a, 1, played = true); episode(b, 2)

        val vm = vm()
        vm.uiState.test {
            awaitUntil { !it.isLoading }
            vm.onIntent(SubscriptionsIntent.SetUnplayedOnly(true))
            val state = awaitUntil { it.unplayedOnly }
            assertEquals(listOf("B"), state.podcasts.map { it.title })
        }
    }

    @Test
    fun `switches between grid and list`() = runTest {
        val vm = vm()
        vm.uiState.test {
            awaitUntil { !it.isLoading }
            vm.onIntent(SubscriptionsIntent.SetLayout(LibraryLayout.LIST))
            assertEquals(LibraryLayout.LIST, awaitUntil { it.layout == LibraryLayout.LIST }.layout)
        }
    }

    @Test
    fun `badge counts follow episodes being played`() = runTest {
        val a = podcast("A")
        episode(a, 1); episode(a, 2)

        vm().uiState.test {
            assertEquals(2, awaitUntil { it.podcasts.isNotEmpty() }.podcasts.single().unplayedCount)
            episodes.update(episodes.getForPodcast(a).first().copy(played = true))
            assertEquals(1, awaitUntil { it.podcasts.singleOrNull()?.unplayedCount == 1 }.podcasts.single().unplayedCount)
        }
    }

    @Test
    fun `pull to refresh triggers a background sync and says so`() = runTest {
        val vm = vm()
        vm.events.test {
            vm.onIntent(SubscriptionsIntent.Refresh)
            assertEquals(SubscriptionsEvent.SyncStarted, awaitItem())
        }
        assertEquals(1, sync.requests)
    }

    @Test
    fun `the refreshing indicator mirrors the background sync`() = runTest {
        vm().uiState.test {
            assertFalse(awaitUntil { !it.isLoading }.isRefreshing)
            sync.running.value = true
            assertTrue(awaitUntil { it.isRefreshing }.isRefreshing)
            sync.running.value = false
            assertFalse(awaitUntil { !it.isRefreshing }.isRefreshing)
        }
    }

    @Test
    fun `reports how many episodes are queued`() = runTest {
        vm().uiState.test {
            awaitUntil { !it.isLoading }
            queue.enqueue(5); queue.enqueue(6)
            assertEquals(2, awaitUntil { it.queueSize == 2 }.queueSize)
        }
    }

    @Test
    fun `starts in the layout the user last chose`() = runTest {
        preferences.setLayout(LibraryLayout.LIST)

        vm().uiState.test {
            assertEquals(LibraryLayout.LIST, awaitUntil { !it.isLoading }.layout)
        }
    }

    @Test
    fun `choosing a layout remembers it`() = runTest {
        val vm = vm()
        vm.uiState.test {
            awaitUntil { !it.isLoading }
            vm.onIntent(SubscriptionsIntent.SetLayout(LibraryLayout.LIST))
            awaitUntil { it.layout == LibraryLayout.LIST }
        }
        assertEquals(LibraryLayout.LIST, preferences.layout.value)
    }
}
