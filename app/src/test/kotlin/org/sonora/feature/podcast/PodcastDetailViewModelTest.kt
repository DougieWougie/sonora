// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.podcast

import app.cash.turbine.test
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
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
import org.sonora.core.media.FakeQueueStorage
import org.sonora.core.media.PersistentPlayQueue
import org.sonora.core.model.DownloadState
import org.sonora.testutil.FakeEpisodeActions
import org.sonora.testutil.FakePlaybackPort
import org.sonora.testutil.FakeSyncController
import org.sonora.testutil.MainDispatcherExtension
import org.sonora.testutil.awaitUntil

class PodcastDetailViewModelTest {
    @JvmField @RegisterExtension
    val main = MainDispatcherExtension()

    private val podcasts = FakePodcastDao()
    private val episodes = FakeEpisodeDao()
    private val changes = FakeSubscriptionChangeDao()
    private val subscriptions = SubscriptionRepository(podcasts, changes, FakeTransactionRunner(), MutableClock())
    private val queue = PersistentPlayQueue(FakeQueueStorage())
    private val actions = FakeEpisodeActions()
    private val sync = FakeSyncController()
    private val playback = FakePlaybackPort()

    private var podcastId = 0L

    private suspend fun seed() {
        podcastId = podcasts.insert(PodcastEntity(feedUrl = "https://f/feed", title = "Show", author = "Ann", description = "About"))
        fun ep(guid: String, title: String, published: Long, played: Boolean = false, state: DownloadState = DownloadState.NONE, desc: String? = null) =
            EpisodeEntity(podcastId = podcastId, guid = guid, title = title, description = desc, enclosureUrl = "https://e/$guid",
                publishedAtMillis = published, played = played, downloadState = state)
        episodes.insertAll(
            listOf(
                ep("1", "Intro to Space", 100, played = true),
                ep("2", "Mars landing", 200, state = DownloadState.DOWNLOADED),
                ep("3", "Cooking basics", 300, desc = "space-saving tips"),
            ),
        )
    }

    private fun vm() = PodcastDetailViewModel(
        SavedStateHandle(mapOf(PodcastDetailViewModel.ARG_PODCAST_ID to podcastId)),
        podcasts, episodes, queue, subscriptions, actions, sync, playback,
    )

    private fun idOf(title: String) = episodes.rows.value.first { it.title == title }.id

    @Test
    fun `shows the podcast and its episodes newest first`() = runTest {
        seed()
        vm().uiState.test {
            val state = awaitUntil { it.episodes.isNotEmpty() }
            assertEquals("Show", state.podcast?.title)
            assertEquals(listOf("Cooking basics", "Mars landing", "Intro to Space"), state.episodes.map { it.title })
            assertTrue(state.subscribed)
        }
    }

    @Test
    fun `filters to unplayed or downloaded episodes`() = runTest {
        seed()
        val vm = vm()
        vm.uiState.test {
            awaitUntil { it.episodes.isNotEmpty() }
            vm.onIntent(PodcastDetailIntent.SetFilter(EpisodeFilter.UNPLAYED))
            assertEquals(listOf("Cooking basics", "Mars landing"), awaitUntil { it.filter == EpisodeFilter.UNPLAYED }.episodes.map { it.title })
            vm.onIntent(PodcastDetailIntent.SetFilter(EpisodeFilter.DOWNLOADED))
            assertEquals(listOf("Mars landing"), awaitUntil { it.filter == EpisodeFilter.DOWNLOADED }.episodes.map { it.title })
        }
    }

    @Test
    fun `search matches titles and descriptions ignoring case`() = runTest {
        seed()
        val vm = vm()
        vm.uiState.test {
            awaitUntil { it.episodes.isNotEmpty() }
            vm.onIntent(PodcastDetailIntent.SetQuery("SPACE"))
            assertEquals(
                listOf("Cooking basics", "Intro to Space"),
                awaitUntil { it.query == "SPACE" }.episodes.map { it.title },
            )
        }
    }

    @Test
    fun `search and filter combine`() = runTest {
        seed()
        val vm = vm()
        vm.uiState.test {
            awaitUntil { it.episodes.isNotEmpty() }
            vm.onIntent(PodcastDetailIntent.SetQuery("space"))
            vm.onIntent(PodcastDetailIntent.SetFilter(EpisodeFilter.UNPLAYED))
            assertEquals(listOf("Cooking basics"), awaitUntil { it.filter == EpisodeFilter.UNPLAYED && it.query == "space" }.episodes.map { it.title })
        }
    }

    @Test
    fun `queueing marks the episode queued`() = runTest {
        seed()
        val vm = vm()
        val id = idOf("Mars landing")
        vm.uiState.test {
            awaitUntil { it.episodes.isNotEmpty() }
            vm.onIntent(PodcastDetailIntent.Enqueue(id))
            assertTrue(awaitUntil { s -> s.episodes.first { it.id == id }.queued }.episodes.first { it.id == id }.queued)
            vm.onIntent(PodcastDetailIntent.Dequeue(id))
            assertFalse(awaitUntil { s -> !s.episodes.first { it.id == id }.queued }.episodes.first { it.id == id }.queued)
        }
        assertEquals(emptyList<Long>(), queue.items.value)
    }

    @Test
    fun `download, delete and played actions are delegated`() = runTest {
        seed()
        val vm = vm()
        val id = idOf("Cooking basics")
        vm.uiState.test {
            awaitUntil { it.episodes.isNotEmpty() }
            vm.onIntent(PodcastDetailIntent.Download(id))
            vm.onIntent(PodcastDetailIntent.DeleteDownload(id))
            vm.onIntent(PodcastDetailIntent.SetPlayed(id, true))
            vm.onIntent(PodcastDetailIntent.SetPlayed(id, false))
            testScheduler.advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf("download:$id", "delete:$id", "played:$id", "unplayed:$id"), actions.calls)
    }

    @Test
    fun `tapping an episode plays it`() = runTest {
        seed()
        val vm = vm()
        val id = idOf("Mars landing")
        vm.uiState.test {
            awaitUntil { it.episodes.isNotEmpty() }
            vm.onIntent(PodcastDetailIntent.Play(id))
            testScheduler.advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf("play:$id"), playback.calls)
    }

    @Test
    fun `a playback failure becomes a message, not a crash`() = runTest {
        seed()
        playback.failure = IllegalStateException("Refusing to stream http://x")
        val vm = vm()
        vm.events.test {
            vm.onIntent(PodcastDetailIntent.Play(idOf("Mars landing")))
            val event = awaitItem() as PodcastDetailEvent.Message
            assertTrue("Refusing" in event.text)
        }
    }

    @Test
    fun `unsubscribing removes the podcast but keeps the header so you can resubscribe`() = runTest {
        seed()
        val vm = vm()
        vm.uiState.test {
            awaitUntil { it.episodes.isNotEmpty() }
            vm.onIntent(PodcastDetailIntent.ToggleSubscription)
            val state = awaitUntil { !it.subscribed }
            assertEquals("Show", state.podcast?.title)
            assertTrue(state.episodes.isEmpty())
            assertFalse(changes.rows.getValue("https://f/feed").subscribed)

            vm.onIntent(PodcastDetailIntent.ToggleSubscription)
            assertTrue(awaitUntil { it.subscribed }.subscribed)
            assertTrue(changes.rows.getValue("https://f/feed").subscribed)
        }
    }

    @Test
    fun `resubscribing triggers a sync so the episodes come back`() = runTest {
        seed()
        val vm = vm()
        vm.uiState.test {
            awaitUntil { it.episodes.isNotEmpty() }
            vm.onIntent(PodcastDetailIntent.ToggleSubscription)
            awaitUntil { !it.subscribed }
            vm.onIntent(PodcastDetailIntent.ToggleSubscription)
            awaitUntil { it.subscribed }
        }
        assertEquals(1, sync.requests)
    }

    @Test
    fun `toggles auto download for the podcast`() = runTest {
        seed()
        val vm = vm()
        vm.uiState.test {
            assertFalse(awaitUntil { it.podcast != null }.podcast!!.autoDownload)
            vm.onIntent(PodcastDetailIntent.ToggleAutoDownload)
            assertTrue(awaitUntil { it.podcast?.autoDownload == true }.podcast!!.autoDownload)
        }
    }

    @Test
    fun `refresh asks for a background sync`() = runTest {
        seed()
        val vm = vm()
        vm.onIntent(PodcastDetailIntent.Refresh)
        testScheduler.advanceUntilIdle()
        assertEquals(1, sync.requests)
        assertNotNull(vm)
    }
}
