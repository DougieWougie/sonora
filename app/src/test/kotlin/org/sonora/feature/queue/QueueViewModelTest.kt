// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.queue

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

class QueueViewModelTest {
    @JvmField @RegisterExtension
    val main = MainDispatcherExtension()

    private val episodes = FakeEpisodeDao()
    private val podcasts = FakePodcastDao()
    private val queue = PersistentPlayQueue(FakeQueueStorage())
    private val actions = FakeEpisodeActions()
    private val playback = FakePlaybackPort()

    private fun vm() = QueueViewModel(episodes, podcasts, queue, actions, playback)

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
    fun `the queue is loaded with one query, not one per episode`() = runTest {
        val ids = seed()
        ids.take(3).forEach { queue.enqueue(it) }
        episodes.findByIdCalls = 0

        vm().uiState.test {
            awaitUntil { it.queue.size == 3 }
            assertEquals(0, episodes.findByIdCalls)
            assertEquals(1, episodes.rowQueries)
        }
    }

    @Test
    fun `a queue longer than SQLites variable limit is loaded in chunks and keeps its order`() = runTest {
        val p = podcasts.insert(PodcastEntity(feedUrl = "https://a", title = "Alpha"))
        episodes.insertAll(
            (1..1_200).map { EpisodeEntity(podcastId = p, guid = "g$it", title = "Ep $it", enclosureUrl = "https://e/$it") },
        )
        val ids = episodes.rows.value.map { it.id }.reversed()
        ids.forEach { queue.enqueue(it) }

        vm().uiState.test {
            val state = awaitUntil { it.queue.size == 1_200 }
            assertEquals(ids, state.queue.map { it.id })
        }
    }

    @Test
    fun `new lists unplayed episodes from every podcast, newest first`() = runTest {
        seed()

        vm().uiState.test {
            val state = awaitUntil { it.new.isNotEmpty() }
            assertEquals(listOf("Ep 2", "Ep 3", "Ep 1"), state.new.map { it.title })
            assertEquals(listOf("Beta", "Alpha", "Alpha"), state.new.map { it.podcastTitle })
        }
    }

    @Test
    fun `new flags episodes that are already queued and drops ones that get played`() = runTest {
        val ids = seed()
        queue.enqueue(ids[1])
        val vm = vm()

        vm.uiState.test {
            val state = awaitUntil { it.new.isNotEmpty() }
            assertEquals(listOf(true, false, false), state.new.map { it.queued })
            episodes.update(episodes.rows.value.first { it.id == ids[1] }.copy(played = true))
            assertEquals(listOf("Ep 3", "Ep 1"), awaitUntil { it.new.size == 2 }.new.map { it.title })
        }
    }

    @Test
    fun `new is capped so a big backlog stays cheap to show`() = runTest {
        val p = podcasts.insert(PodcastEntity(feedUrl = "https://a", title = "Alpha"))
        episodes.insertAll(
            (1..NEW_EPISODES_LIMIT + 5).map {
                EpisodeEntity(podcastId = p, guid = "g$it", title = "Ep $it", enclosureUrl = "https://e/$it", publishedAtMillis = it.toLong())
            },
        )

        vm().uiState.test {
            val state = awaitUntil { it.new.isNotEmpty() }
            assertEquals(NEW_EPISODES_LIMIT, state.new.size)
            assertEquals("Ep ${NEW_EPISODES_LIMIT + 5}", state.new.first().title)
        }
    }

    @Test
    fun `enqueue, dequeue and clear update the queue`() = runTest {
        val ids = seed()
        val vm = vm()
        vm.uiState.test {
            awaitUntil { !it.isLoading }
            vm.onIntent(QueueIntent.Enqueue(ids[0])); vm.onIntent(QueueIntent.Enqueue(ids[1]))
            awaitUntil { it.queue.size == 2 }
            vm.onIntent(QueueIntent.Dequeue(ids[0]))
            assertEquals(listOf("Ep 2"), awaitUntil { it.queue.size == 1 }.queue.map { it.title })
            vm.onIntent(QueueIntent.ClearQueue)
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
            vm.onIntent(QueueIntent.PlayQueue)
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
            vm.onIntent(QueueIntent.ShuffleQueue)
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
            vm.onIntent(QueueIntent.PlayQueue); vm.onIntent(QueueIntent.ShuffleQueue)
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
            vm.onIntent(QueueIntent.PlayNext(ids[2]))
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
            vm.onIntent(QueueIntent.MoveInQueue(ids[0], 2))
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
                vm.onIntent(QueueIntent.Dequeue(ids[1]))
                val event = awaitItem() as QueueEvent.QueueChanged
                assertEquals(listOf(ids[0], ids[1], ids[2]), event.previous)
                assertEquals(listOf(ids[0], ids[2]), queue.items.value)
                vm.onIntent(QueueIntent.RestoreQueue(event.previous))
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
            vm.onIntent(QueueIntent.RequestClearQueue)
            assertEquals(true, awaitUntil { it.confirmingClear }.confirmingClear)
            assertEquals(listOf(ids[0]), queue.items.value)
            vm.onIntent(QueueIntent.DismissClearQueue)
            awaitUntil { !it.confirmingClear }
            vm.onIntent(QueueIntent.RequestClearQueue)
            awaitUntil { it.confirmingClear }
            vm.onIntent(QueueIntent.ClearQueue)
            val cleared = awaitUntil { it.queue.isEmpty() }
            assertEquals(false, cleared.confirmingClear)
        }
    }

    @Test
    fun `playing from new asks where to put it when the queue has episodes`() = runTest {
        val ids = seed()
        queue.enqueue(ids[0])
        val vm = vm()
        vm.uiState.test {
            awaitUntil { !it.isLoading }
            vm.onIntent(QueueIntent.Play(ids[1]))
            assertEquals(ids[1], awaitUntil { it.pendingPlay != null }.pendingPlay)
            assertEquals(emptyList<String>(), playback.calls)

            vm.onIntent(QueueIntent.ConfirmPlayNext)
            assertNull(awaitUntil { it.pendingPlay == null }.pendingPlay)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf(ids[1], ids[0]), queue.items.value)
    }

    @Test
    fun `adding to the end and cancelling are both offered`() = runTest {
        val ids = seed()
        queue.enqueue(ids[0])
        val vm = vm()
        vm.uiState.test {
            awaitUntil { !it.isLoading }
            vm.onIntent(QueueIntent.Play(ids[1]))
            awaitUntil { it.pendingPlay != null }
            vm.onIntent(QueueIntent.ConfirmAddToEnd)
            awaitUntil { it.pendingPlay == null }
            assertEquals(listOf(ids[0], ids[1]), queue.items.value)

            vm.onIntent(QueueIntent.Play(ids[2]))
            awaitUntil { it.pendingPlay != null }
            vm.onIntent(QueueIntent.DismissPlayChoice)
            awaitUntil { it.pendingPlay == null }
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf(ids[0], ids[1]), queue.items.value)
    }

    @Test
    fun `tapping an episode inside the queue plays it without asking`() = runTest {
        val ids = seed()
        queue.enqueue(ids[0]); queue.enqueue(ids[1])
        val vm = vm()
        vm.uiState.test {
            awaitUntil { !it.isLoading }
            vm.onIntent(QueueIntent.PlayQueued(ids[1]))
            testScheduler.advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf("play:${ids[1]}"), playback.calls)
    }

    @Test
    fun `play, download and mark played are delegated`() = runTest {
        val ids = seed()
        val vm = vm()
        vm.uiState.test {
            awaitUntil { !it.isLoading }
            vm.onIntent(QueueIntent.Play(ids[0]))
            vm.onIntent(QueueIntent.Download(ids[1]))
            vm.onIntent(QueueIntent.SetPlayed(ids[2], true))
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
            vm.onIntent(QueueIntent.DeleteDownload(ids[1]))
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
            vm.onIntent(QueueIntent.RequestMarkOlderPlayed(ids[2]))
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
            vm.onIntent(QueueIntent.RequestMarkOlderPlayed(id))
            awaitUntil { it.pendingMarkOlder != null }
            vm.onIntent(QueueIntent.ConfirmMarkOlderPlayed)
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
            vm.onIntent(QueueIntent.RequestMarkOlderPlayed(id))
            awaitUntil { it.pendingMarkOlder != null }
            vm.onIntent(QueueIntent.DismissMarkOlderPlayed)
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
            vm.onIntent(QueueIntent.RequestMarkOlderPlayed(ids[0]))
            assertEquals(0, awaitUntil { it.pendingMarkOlder != null }.pendingMarkOlder!!.count)
            vm.onIntent(QueueIntent.ConfirmMarkOlderPlayed)
            awaitUntil { it.pendingMarkOlder == null }
            testScheduler.advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(emptyList<String>(), actions.calls)
    }

    @Test
    fun `removing from the queue says Queue`() = runTest {
        val ids = seed()
        queue.enqueue(ids[0])
        val vm = vm()
        vm.events.test {
            vm.onIntent(QueueIntent.Dequeue(ids[0]))
            assertEquals("Removed from Queue", (awaitItem() as QueueEvent.QueueChanged).text)
        }
    }
}
