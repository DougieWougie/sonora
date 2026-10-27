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
import org.sonora.testutil.FakeSettingsStore
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
    private val settings = FakeSettingsStore()

    private fun vm() = EpisodesViewModel(episodes, podcasts, queue, actions, playback, settings)

    private suspend fun seed(): List<Long> {
        val a = podcasts.insert(PodcastEntity(feedUrl = "https://a", title = "Alpha"))
        val b = podcasts.insert(PodcastEntity(feedUrl = "https://b", title = "Beta"))
        fun ep(p: Long, n: Int, published: Long, played: Boolean = false) =
            EpisodeEntity(podcastId = p, guid = "$p-$n", title = "Ep $n", enclosureUrl = "https://e/$p/$n", publishedAtMillis = published, played = played)
        episodes.insertAll(listOf(ep(a, 1, 100), ep(b, 2, 300), ep(a, 3, 200), ep(b, 4, 400, played = true)))
        return episodes.rows.value.map { it.id }
    }

    @Test
    fun `the queue keeps its order and flags its episodes as queued`() = runTest {
        val ids = seed()
        queue.enqueue(ids[2]); queue.enqueue(ids[0])

        vm().uiState.test {
            val state = awaitUntil { it.queue.isNotEmpty() }
            assertEquals(listOf("Ep 3", "Ep 1"), state.queue.map { it.title })
            assertTrue(state.queue.all { it.queued })
        }
    }

    @Test
    fun `enqueue, dequeue and clear update the queue`() = runTest {
        val ids = seed()
        val vm = vm()
        vm.uiState.test {
            awaitUntil { !it.isLoading }
            vm.onIntent(EpisodesIntent.Enqueue(ids[0])); vm.onIntent(EpisodesIntent.Enqueue(ids[1]))
            awaitUntil { it.queue.size == 2 }
            vm.onIntent(EpisodesIntent.Dequeue(ids[0]))
            assertEquals(listOf("Ep 2"), awaitUntil { it.queue.size == 1 }.queue.map { it.title })
            vm.onIntent(EpisodesIntent.ClearQueue)
            assertTrue(awaitUntil { it.queue.isEmpty() }.queue.isEmpty())
        }
    }

    @Test
    fun `playing the queue starts its first episode and leaves the order alone`() = runTest {
        val ids = seed()
        queue.enqueue(ids[2]); queue.enqueue(ids[0]); queue.enqueue(ids[1])
        val vm = vm()
        vm.uiState.test {
            awaitUntil { it.queue.size == 3 }
            vm.onIntent(EpisodesIntent.PlayQueue)
            testScheduler.advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf("play:${ids[2]}"), playback.calls)
        assertEquals(listOf(ids[2], ids[0], ids[1]), queue.items.value)
    }

    @Test
    fun `shuffling the queue reorders it and plays whatever comes first`() = runTest {
        val ids = seed()
        ids.forEach(queue::enqueue)
        val vm = vm()
        vm.uiState.test {
            awaitUntil { it.queue.size == 4 }
            vm.onIntent(EpisodesIntent.ShuffleQueue)
            testScheduler.advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(ids.toSet(), queue.items.value.toSet())
        assertEquals(listOf("play:${queue.items.value.first()}"), playback.calls)
    }

    @Test
    fun `playing an empty queue does nothing`() = runTest {
        val vm = vm()
        vm.uiState.test {
            awaitItem()
            vm.onIntent(EpisodesIntent.PlayQueue); vm.onIntent(EpisodesIntent.ShuffleQueue)
            testScheduler.advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(playback.calls.isEmpty())
    }

    @Test
    fun `play next puts an episode at the head of the queue`() = runTest {
        val ids = seed()
        queue.enqueue(ids[0]); queue.enqueue(ids[1])
        val vm = vm()
        vm.uiState.test {
            awaitUntil { it.queue.size == 2 }
            vm.onIntent(EpisodesIntent.PlayNext(ids[2]))
            assertEquals(listOf(ids[2], ids[0], ids[1]), awaitUntil { it.queue.size == 3 }.queue.map { it.id })
        }
    }

    @Test
    fun `moving a queued episode reorders the queue`() = runTest {
        val ids = seed()
        ids.take(3).forEach(queue::enqueue)
        val vm = vm()
        vm.uiState.test {
            awaitUntil { it.queue.size == 3 }
            vm.onIntent(EpisodesIntent.MoveInQueue(ids[0], 2))
            assertEquals(listOf(ids[1], ids[2], ids[0]), awaitUntil { s -> s.queue.first().id == ids[1] }.queue.map { it.id })
        }
    }

    @Test
    fun `removing from the queue offers an undo that restores the old order`() = runTest {
        val ids = seed()
        ids.take(3).forEach(queue::enqueue)
        val vm = vm()
        vm.uiState.test {
            awaitUntil { it.queue.size == 3 }
            vm.events.test {
                vm.onIntent(EpisodesIntent.Dequeue(ids[1]))
                val event = awaitItem() as EpisodesEvent.QueueChanged
                assertEquals(listOf(ids[0], ids[1], ids[2]), event.previous)
                assertEquals(listOf(ids[0], ids[2]), queue.items.value)
                vm.onIntent(EpisodesIntent.RestoreQueue(event.previous))
            }
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(ids.take(3), queue.items.value)
    }

    @Test
    fun `clearing the queue asks first and can be cancelled`() = runTest {
        val ids = seed()
        queue.enqueue(ids[0])
        val vm = vm()
        vm.uiState.test {
            awaitUntil { it.queue.size == 1 }
            vm.onIntent(EpisodesIntent.RequestClearQueue)
            assertEquals(true, awaitUntil { it.confirmingClear }.confirmingClear)
            assertEquals(listOf(ids[0]), queue.items.value)
            vm.onIntent(EpisodesIntent.DismissClearQueue)
            awaitUntil { !it.confirmingClear }
            vm.onIntent(EpisodesIntent.RequestClearQueue)
            awaitUntil { it.confirmingClear }
            vm.onIntent(EpisodesIntent.ClearQueue)
            val cleared = awaitUntil { it.queue.isEmpty() }
            assertEquals(false, cleared.confirmingClear)
        }
    }

    @Test
    fun `play, download and mark played are delegated`() = runTest {
        val ids = seed()
        val vm = vm()
        vm.uiState.test {
            awaitUntil { !it.isLoading }
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
            awaitUntil { !it.isLoading }
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
            awaitUntil { !it.isLoading }
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
            awaitUntil { !it.isLoading }
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
            awaitUntil { !it.isLoading }
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
            awaitUntil { !it.isLoading }
            vm.onIntent(EpisodesIntent.RequestMarkOlderPlayed(ids[0]))
            assertEquals(0, awaitUntil { it.pendingMarkOlder != null }.pendingMarkOlder!!.count)
            vm.onIntent(EpisodesIntent.ConfirmMarkOlderPlayed)
            awaitUntil { it.pendingMarkOlder == null }
            testScheduler.advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(emptyList<String>(), actions.calls)
    }

    // --- collapsible sections ----------------------------------------------------------------

    @Test
    fun `the queue starts expanded`() = runTest {
        vm().uiState.test {
            assertTrue(awaitUntil { !it.isLoading }.queueExpanded)
        }
    }

    @Test
    fun `the queue starts as it was left`() = runTest {
        settings.queueCollapsed = true

        vm().uiState.test {
            assertEquals(false, awaitUntil { !it.isLoading }.queueExpanded)
        }
    }

    @Test
    fun `toggling the queue flips it and saves it`() = runTest {
        val vm = vm()
        vm.uiState.test {
            awaitUntil { !it.isLoading }

            vm.onIntent(EpisodesIntent.ToggleSection(EpisodesSection.QUEUE))
            awaitUntil { !it.queueExpanded }
            assertTrue(settings.queueCollapsed)

            vm.onIntent(EpisodesIntent.ToggleSection(EpisodesSection.QUEUE))
            awaitUntil { it.queueExpanded }
            assertEquals(false, settings.queueCollapsed)
        }
    }

    @Test
    fun `removing from the queue says Queue`() = runTest {
        val ids = seed()
        queue.enqueue(ids[0])
        val vm = vm()
        vm.events.test {
            vm.onIntent(EpisodesIntent.Dequeue(ids[0]))
            assertEquals("Removed from Queue", (awaitItem() as EpisodesEvent.QueueChanged).text)
        }
    }
}
