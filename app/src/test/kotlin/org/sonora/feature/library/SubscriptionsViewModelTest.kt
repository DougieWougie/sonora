// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.library

import app.cash.turbine.test
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.sonora.core.database.EpisodeEntity
import org.sonora.core.database.FakeEpisodeDao
import org.sonora.core.database.FakePodcastDao
import org.sonora.core.database.FakeSubscriptionChangeDao
import org.sonora.core.database.FakeTransactionRunner
import org.sonora.core.database.MutableClock
import org.sonora.core.database.PodcastEntity
import org.sonora.core.database.SubscriptionRepository
import org.sonora.core.media.PersistentPlayQueue
import org.sonora.core.media.FakeQueueStorage
import org.sonora.testutil.FakeEpisodeActions
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

    private val changes = FakeSubscriptionChangeDao()
    private val subscriptions = SubscriptionRepository(podcasts, changes, FakeTransactionRunner(), MutableClock())
    private val actions = FakeEpisodeActions()

    private fun vm() = SubscriptionsViewModel(podcasts, episodes, queue, sync, preferences, subscriptions, actions)

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

    @Test
    fun `starts with the sort and filter the user last chose`() = runTest {
        preferences.setSort(LibrarySort.RECENT)
        preferences.setUnplayedOnly(true)

        vm().uiState.test {
            val state = awaitUntil { !it.isLoading }
            assertEquals(LibrarySort.RECENT, state.sort)
            assertTrue(state.unplayedOnly)
        }
    }

    @Test
    fun `choosing a sort order or the unplayed filter remembers it`() = runTest {
        val vm = vm()
        vm.uiState.test {
            awaitUntil { !it.isLoading }
            vm.onIntent(SubscriptionsIntent.SetSort(LibrarySort.UNPLAYED))
            awaitUntil { it.sort == LibrarySort.UNPLAYED }
            vm.onIntent(SubscriptionsIntent.SetUnplayedOnly(true))
            awaitUntil { it.unplayedOnly }
        }
        assertEquals(LibrarySort.UNPLAYED, preferences.sort.value)
        assertTrue(preferences.unplayedOnly.value)
    }

    // --- search ------------------------------------------------------------------------------

    @Test
    fun `searching narrows the library by title or author, ignoring case`() = runTest {
        podcasts.insert(PodcastEntity(feedUrl = "https://f/1", title = "Space Talk", author = "Neil"))
        podcasts.insert(PodcastEntity(feedUrl = "https://f/2", title = "Cooking", author = "Space Chef"))
        podcasts.insert(PodcastEntity(feedUrl = "https://f/3", title = "History", author = "Ann"))
        val vm = vm()
        vm.uiState.test {
            awaitUntil { it.podcasts.size == 3 }

            vm.onIntent(SubscriptionsIntent.SetQuery("  SPACE "))
            val state = awaitUntil { it.query.isNotEmpty() }

            assertEquals("  SPACE ", state.query)
            assertEquals(listOf("Cooking", "Space Talk"), state.podcasts.map { it.title })
        }
    }

    @Test
    fun `clearing the search restores the library and search combines with the unplayed filter`() = runTest {
        val a = podcast("Alpha")
        podcast("Alpine")
        episode(a, 1)
        val vm = vm()
        vm.uiState.test {
            awaitUntil { it.podcasts.size == 2 }

            vm.onIntent(SubscriptionsIntent.SetQuery("alp"))
            awaitUntil { it.query == "alp" }
            vm.onIntent(SubscriptionsIntent.SetUnplayedOnly(true))
            assertEquals(listOf("Alpha"), awaitUntil { it.unplayedOnly }.podcasts.map { it.title })

            vm.onIntent(SubscriptionsIntent.SetUnplayedOnly(false))
            vm.onIntent(SubscriptionsIntent.SetQuery(""))
            assertEquals(2, awaitUntil { it.query.isEmpty() && !it.unplayedOnly }.podcasts.size)
        }
    }

    // --- per-show actions --------------------------------------------------------------------

    @Test
    fun `marking a show played delegates and says how many episodes changed`() = runTest {
        val id = podcast("Alpha")
        actions.unplayedCounts[id] = 3
        val vm = vm()
        vm.events.test {
            vm.onIntent(SubscriptionsIntent.MarkAllPlayed(id))
            assertEquals(SubscriptionsEvent.Message("Marked 3 episodes played"), awaitItem())
        }
        assertEquals(listOf("all:$id"), actions.calls)
    }

    @Test
    fun `one episode is singular`() = runTest {
        val id = podcast("Alpha")
        actions.unplayedCounts[id] = 1
        val vm = vm()
        vm.events.test {
            vm.onIntent(SubscriptionsIntent.MarkAllPlayed(id))
            assertEquals(SubscriptionsEvent.Message("Marked 1 episode played"), awaitItem())
        }
    }

    @Test
    fun `auto-download is toggled for that show only`() = runTest {
        val a = podcast("Alpha")
        val b = podcast("Beta")
        val vm = vm()
        vm.uiState.test {
            awaitUntil { it.podcasts.size == 2 }

            vm.onIntent(SubscriptionsIntent.ToggleAutoDownload(a))
            val on = awaitUntil { s -> s.podcasts.first { it.id == a }.autoDownload }
            assertFalse(on.podcasts.first { it.id == b }.autoDownload)

            vm.onIntent(SubscriptionsIntent.ToggleAutoDownload(a))
            assertFalse(awaitUntil { s -> !s.podcasts.first { it.id == a }.autoDownload }.podcasts.first { it.id == a }.autoDownload)
        }
    }

    @Test
    fun `unsubscribing asks first and can be cancelled`() = runTest {
        val id = podcast("Alpha")
        val vm = vm()
        vm.uiState.test {
            awaitUntil { it.podcasts.size == 1 }

            vm.onIntent(SubscriptionsIntent.RequestUnsubscribe(id))
            assertEquals("Alpha", awaitUntil { it.confirmingUnsubscribe != null }.confirmingUnsubscribe?.title)
            assertEquals(1, podcasts.observeAll().first().size)

            vm.onIntent(SubscriptionsIntent.DismissUnsubscribe)
            assertEquals(null, awaitUntil { it.confirmingUnsubscribe == null }.confirmingUnsubscribe)
            assertEquals(1, podcasts.observeAll().first().size)
        }
    }

    @Test
    fun `confirming removes the show and says so`() = runTest {
        val id = podcast("Alpha")
        val vm = vm()
        vm.uiState.test {
            awaitUntil { it.podcasts.size == 1 }
            vm.onIntent(SubscriptionsIntent.RequestUnsubscribe(id))
            awaitUntil { it.confirmingUnsubscribe != null }

            vm.events.test {
                vm.onIntent(SubscriptionsIntent.ConfirmUnsubscribe)
                assertEquals(SubscriptionsEvent.Message("Unsubscribed from Alpha"), awaitItem())
            }
            val state = awaitUntil { it.podcasts.isEmpty() }
            assertEquals(null, state.confirmingUnsubscribe)
        }
        assertEquals(false, changes.rows.values.single().subscribed)
    }

    @Test
    fun `requesting to unsubscribe from an unknown show does nothing`() = runTest {
        val vm = vm()
        vm.uiState.test {
            awaitUntil { !it.isLoading }
            vm.onIntent(SubscriptionsIntent.RequestUnsubscribe(999))
            expectNoEvents()
        }
    }
}
