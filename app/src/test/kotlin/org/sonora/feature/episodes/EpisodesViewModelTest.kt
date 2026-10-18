// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.episodes

import app.cash.turbine.test
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.sonora.core.database.EpisodeEntity
import org.sonora.core.database.FakeEpisodeDao
import org.sonora.core.database.FakePodcastDao
import org.sonora.core.database.PodcastEntity
import org.sonora.core.media.FakeQueueStorage
import org.sonora.core.media.PersistentPlayQueue
import org.sonora.feature.common.PendingMarkOlder
import org.sonora.testutil.FakeEpisodeActions
import org.sonora.testutil.FakePlaybackPort
import org.sonora.testutil.MainDispatcherExtension
import org.sonora.testutil.awaitUntil

class EpisodesViewModelTest {
    @JvmField @RegisterExtension
    val main = MainDispatcherExtension()

    private val episodes = FakeEpisodeDao()
    private val podcasts = FakePodcastDao()
    private val queue = PersistentPlayQueue(FakeQueueStorage())
    private val actions = FakeEpisodeActions()
    private val playback = FakePlaybackPort()

    private fun vm() = EpisodesViewModel(episodes, podcasts, queue, actions, playback)

    private suspend fun seed(): List<Long> {
        val a = podcasts.insert(PodcastEntity(feedUrl = "https://a", title = "Alpha"))
        val b = podcasts.insert(PodcastEntity(feedUrl = "https://b", title = "Beta"))
        fun ep(p: Long, n: Int, published: Long, played: Boolean = false) =
            EpisodeEntity(podcastId = p, guid = "$p-$n", title = "Ep $n", enclosureUrl = "https://e/$p/$n", publishedAtMillis = published, played = played)
        episodes.insertAll(listOf(ep(a, 1, 100), ep(b, 2, 300), ep(a, 3, 200), ep(b, 4, 400, played = true)))
        return episodes.rows.value.map { it.id }
    }

    @Test
    fun `the inbox shows unplayed episodes from every podcast, newest first`() = runTest {
        seed()
        vm().uiState.test {
            val state = awaitUntil { it.inbox.isNotEmpty() }
            assertEquals(listOf("Ep 2", "Ep 3", "Ep 1"), state.inbox.map { it.title })
            assertEquals(listOf("Beta", "Alpha", "Alpha"), state.inbox.map { it.podcastTitle })
        }
    }

    @Test
    fun `the queue keeps its order and flags queued episodes in the inbox`() = runTest {
        val ids = seed()
        queue.enqueue(ids[2]); queue.enqueue(ids[0])

        vm().uiState.test {
            val state = awaitUntil { it.queue.isNotEmpty() }
            assertEquals(listOf("Ep 3", "Ep 1"), state.queue.map { it.title })
            assertEquals(setOf("Ep 3", "Ep 1"), state.inbox.filter { it.queued }.map { it.title }.toSet())
        }
    }

    @Test
    fun `enqueue, dequeue and clear update the queue`() = runTest {
        val ids = seed()
        val vm = vm()
        vm.uiState.test {
            awaitUntil { it.inbox.isNotEmpty() }
            vm.onIntent(EpisodesIntent.Enqueue(ids[0])); vm.onIntent(EpisodesIntent.Enqueue(ids[1]))
            awaitUntil { it.queue.size == 2 }
            vm.onIntent(EpisodesIntent.Dequeue(ids[0]))
            assertEquals(listOf("Ep 2"), awaitUntil { it.queue.size == 1 }.queue.map { it.title })
            vm.onIntent(EpisodesIntent.ClearQueue)
            assertTrue(awaitUntil { it.queue.isEmpty() }.queue.isEmpty())
        }
    }

    @Test
    fun `play, download and mark played are delegated`() = runTest {
        val ids = seed()
        val vm = vm()
        vm.uiState.test {
            awaitUntil { it.inbox.isNotEmpty() }
            vm.onIntent(EpisodesIntent.Play(ids[0]))
            vm.onIntent(EpisodesIntent.Download(ids[1]))
            vm.onIntent(EpisodesIntent.SetPlayed(ids[2], true))
            testScheduler.advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf("play:${ids[0]}"), playback.calls)
        assertEquals(listOf("download:${ids[1]}", "played:${ids[2]}"), actions.calls)
    }

    @Test
    fun `deleting a download is delegated`() = runTest {
        val ids = seed()
        val vm = vm()
        vm.uiState.test {
            awaitUntil { it.inbox.isNotEmpty() }
            vm.onIntent(EpisodesIntent.DeleteDownload(ids[1]))
            testScheduler.advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf("delete:${ids[1]}"), actions.calls)
    }

    @Test
    fun `asking to mark older episodes played waits for confirmation and shows the count`() = runTest {
        val ids = seed()
        actions.olderCounts[ids[2]] = 2
        val vm = vm()
        vm.uiState.test {
            awaitUntil { it.inbox.isNotEmpty() }
            vm.onIntent(EpisodesIntent.RequestMarkOlderPlayed(ids[2]))
            assertEquals(
                PendingMarkOlder(ids[2], 2),
                awaitUntil { it.pendingMarkOlder != null }.pendingMarkOlder,
            )
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(emptyList<String>(), actions.calls)
    }

    @Test
    fun `confirming marks the older episodes played and closes the dialog`() = runTest {
        val ids = seed()
        val id = ids[2]
        actions.olderCounts[id] = 2
        val vm = vm()
        vm.uiState.test {
            awaitUntil { it.inbox.isNotEmpty() }
            vm.onIntent(EpisodesIntent.RequestMarkOlderPlayed(id))
            awaitUntil { it.pendingMarkOlder != null }
            vm.onIntent(EpisodesIntent.ConfirmMarkOlderPlayed)
            assertNull(awaitUntil { it.pendingMarkOlder == null }.pendingMarkOlder)
            testScheduler.advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf("older:$id"), actions.calls)
    }

    @Test
    fun `dismissing the dialog marks nothing`() = runTest {
        val ids = seed()
        val id = ids[2]
        actions.olderCounts[id] = 2
        val vm = vm()
        vm.uiState.test {
            awaitUntil { it.inbox.isNotEmpty() }
            vm.onIntent(EpisodesIntent.RequestMarkOlderPlayed(id))
            awaitUntil { it.pendingMarkOlder != null }
            vm.onIntent(EpisodesIntent.DismissMarkOlderPlayed)
            assertNull(awaitUntil { it.pendingMarkOlder == null }.pendingMarkOlder)
            testScheduler.advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(emptyList<String>(), actions.calls)
    }

    @Test
    fun `confirming when there is nothing older marks nothing`() = runTest {
        val ids = seed()
        val vm = vm()
        vm.uiState.test {
            awaitUntil { it.inbox.isNotEmpty() }
            vm.onIntent(EpisodesIntent.RequestMarkOlderPlayed(ids[0]))
            assertEquals(0, awaitUntil { it.pendingMarkOlder != null }.pendingMarkOlder!!.count)
            vm.onIntent(EpisodesIntent.ConfirmMarkOlderPlayed)
            awaitUntil { it.pendingMarkOlder == null }
            testScheduler.advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(emptyList<String>(), actions.calls)
    }
}
