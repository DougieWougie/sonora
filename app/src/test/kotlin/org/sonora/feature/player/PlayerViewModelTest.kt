// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.player

import app.cash.turbine.test
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
import org.sonora.core.media.PlaybackProgress
import org.sonora.testutil.FakePlaybackPort
import org.sonora.testutil.MainDispatcherExtension
import org.sonora.testutil.awaitUntil

class PlayerViewModelTest {
    @JvmField @RegisterExtension
    val main = MainDispatcherExtension()

    private val port = FakePlaybackPort()
    private val episodes = FakeEpisodeDao()
    private val podcasts = FakePodcastDao()
    private val queue = PersistentPlayQueue(FakeQueueStorage())

    private fun vm() = PlayerViewModel(port, episodes, podcasts, queue)

    private suspend fun seed(count: Int = 3): List<Long> {
        val podcastId = podcasts.insert(PodcastEntity(feedUrl = "https://f", title = "Show", imageUrl = "https://art/show.png"))
        episodes.insertAll(
            (1..count).map {
                EpisodeEntity(
                    podcastId = podcastId, guid = "g$it", title = "Episode $it", enclosureUrl = "https://e/$it",
                    imageUrl = if (it == 1) "https://art/ep1.png" else null, durationSeconds = 600,
                )
            },
        )
        return episodes.getForPodcast(podcastId).map { it.id }
    }

    // --- state mapping -----------------------------------------------------------------------

    @Test
    fun `is idle when nothing is loaded`() = runTest {
        vm().uiState.test {
            val state = awaitItem()
            assertNull(state.episode)
            assertFalse(state.isPlaying)
            assertTrue(state.upNext.isEmpty())
        }
    }

    @Test
    fun `maps the current episode, its artwork and the playback state`() = runTest {
        val (first) = seed()
        port.stateFlow.value = port.stateFlow.value.copy(episodeId = first, isPlaying = true, speed = 1.5f, skipSilence = true, sleepTimerRemainingMs = 90_000)
        port.progressFlow.value = PlaybackProgress(positionMs = 12_000, bufferedMs = 40_000, durationMs = 600_000)

        vm().uiState.test {
            val state = awaitUntil { it.episode != null }
            assertEquals("Episode 1", state.episode?.title)
            assertEquals("Show", state.episode?.podcastTitle)
            assertEquals("https://art/ep1.png", state.episode?.artworkUrl)
            assertTrue(state.isPlaying)
            assertEquals(12_000, state.positionMs)
            assertEquals(40_000, state.bufferedMs)
            assertEquals(600_000, state.durationMs)
            assertEquals(1.5f, state.speed)
            assertTrue(state.skipSilence)
            assertEquals(90_000L, state.sleepTimerRemainingMs)
        }
    }

    @Test
    fun `falls back to the podcast artwork`() = runTest {
        val ids = seed()
        port.stateFlow.value = port.stateFlow.value.copy(episodeId = ids[1])
        vm().uiState.test {
            assertEquals("https://art/show.png", awaitUntil { it.episode != null }.episode?.artworkUrl)
        }
    }

    @Test
    fun `position updates flow through as they happen`() = runTest {
        val (first) = seed()
        port.stateFlow.value = port.stateFlow.value.copy(episodeId = first, isPlaying = true)
        vm().uiState.test {
            awaitUntil { it.episode != null }
            port.progressFlow.value = PlaybackProgress(30_000, 50_000, 600_000)
            assertEquals(30_000, awaitUntil { it.positionMs == 30_000L }.positionMs)
        }
    }

    @Test
    fun `lists the queue as up next without the current episode, skipping missing ones`() = runTest {
        val ids = seed(4)
        port.stateFlow.value = port.stateFlow.value.copy(episodeId = ids[0])
        queue.enqueue(ids[0]); queue.enqueue(ids[2]); queue.enqueue(9_999); queue.enqueue(ids[3])

        vm().uiState.test {
            val state = awaitUntil { it.upNext.isNotEmpty() }
            assertEquals(listOf("Episode 3", "Episode 4"), state.upNext.map { it.title })
        }
    }

    // --- intents -----------------------------------------------------------------------------

    @Test
    fun `play pause toggles using the current state`() = runTest {
        val (first) = seed()
        port.stateFlow.value = port.stateFlow.value.copy(episodeId = first, isPlaying = true)
        val vm = vm()
        vm.uiState.test {
            awaitUntil { it.isPlaying }
            vm.onIntent(PlayerIntent.TogglePlayPause)
            awaitUntil { !it.isPlaying }
            vm.onIntent(PlayerIntent.TogglePlayPause)
            awaitUntil { it.isPlaying }
        }
        assertEquals(listOf("pause", "play:$first"), port.calls)
    }

    @Test
    fun `play pause does nothing when nothing is loaded`() = runTest {
        val vm = vm()
        vm.uiState.test {
            awaitItem()
            vm.onIntent(PlayerIntent.TogglePlayPause)
            testScheduler.advanceUntilIdle()
        }
        assertTrue(port.calls.isEmpty())
    }

    @Test
    fun `seeking is forwarded and clamped to the track`() = runTest {
        val (first) = seed()
        port.stateFlow.value = port.stateFlow.value.copy(episodeId = first)
        port.progressFlow.value = PlaybackProgress(0, 0, 600_000)
        val vm = vm()
        vm.uiState.test {
            awaitUntil { it.durationMs == 600_000L }
            vm.onIntent(PlayerIntent.SeekTo(120_000))
            vm.onIntent(PlayerIntent.SeekTo(-5))
            vm.onIntent(PlayerIntent.SeekTo(9_999_999))
            testScheduler.advanceUntilIdle()
        }
        assertEquals(listOf("seek:120000", "seek:0", "seek:600000"), port.calls)
    }

    @Test
    fun `skip forward is 30 seconds and rewind is 15`() = runTest {
        val (first) = seed()
        port.stateFlow.value = port.stateFlow.value.copy(episodeId = first, isPlaying = true)
        port.progressFlow.value = PlaybackProgress(100_000, 0, 600_000)
        val vm = vm()
        vm.uiState.test {
            awaitUntil { it.positionMs == 100_000L }
            vm.onIntent(PlayerIntent.SkipForward)
            vm.onIntent(PlayerIntent.Rewind)
            testScheduler.advanceUntilIdle()
        }
        assertEquals(listOf("seek:130000", "seek:85000"), port.calls)
    }

    @Test
    fun `skipping never passes the end and rewinding never goes below zero`() = runTest {
        val (first) = seed()
        port.stateFlow.value = port.stateFlow.value.copy(episodeId = first)
        port.progressFlow.value = PlaybackProgress(590_000, 0, 600_000)
        val vm = vm()
        vm.uiState.test {
            awaitUntil { it.positionMs == 590_000L }
            vm.onIntent(PlayerIntent.SkipForward)
            testScheduler.advanceUntilIdle()
            port.progressFlow.value = PlaybackProgress(5_000, 0, 600_000)
            awaitUntil { it.positionMs == 5_000L }
            vm.onIntent(PlayerIntent.Rewind)
            testScheduler.advanceUntilIdle()
        }
        assertEquals(listOf("seek:600000", "seek:0"), port.calls)
    }

    @Test
    fun `speed steps by a tenth within 0_5 to 3_0`() = runTest {
        val vm = vm()
        vm.uiState.test {
            awaitItem()
            vm.onIntent(PlayerIntent.StepSpeed(+1)); awaitUntil { it.speed == 1.1f }
            vm.onIntent(PlayerIntent.StepSpeed(-1)); awaitUntil { it.speed == 1.0f }
            vm.onIntent(PlayerIntent.SetSpeed(3.0f)); awaitUntil { it.speed == 3.0f }
            vm.onIntent(PlayerIntent.StepSpeed(+1))
            vm.onIntent(PlayerIntent.SetSpeed(0.5f)); awaitUntil { it.speed == 0.5f }
            vm.onIntent(PlayerIntent.StepSpeed(-1))
            testScheduler.advanceUntilIdle()
        }
        assertEquals(listOf("speed:1.1", "speed:1.0", "speed:3.0", "speed:3.0", "speed:0.5", "speed:0.5"), port.calls)
    }

    @Test
    fun `silence trimming toggles`() = runTest {
        val vm = vm()
        vm.uiState.test {
            awaitItem()
            vm.onIntent(PlayerIntent.ToggleSkipSilence); awaitUntil { it.skipSilence }
            vm.onIntent(PlayerIntent.ToggleSkipSilence); awaitUntil { !it.skipSilence }
        }
        assertEquals(listOf("silence:true", "silence:false"), port.calls)
    }

    @Test
    fun `sleep timer can be set and cancelled`() = runTest {
        val vm = vm()
        vm.uiState.test {
            awaitItem()
            vm.onIntent(PlayerIntent.SetSleepTimer(15 * 60_000L)); assertEquals(900_000L, awaitUntil { it.sleepTimerRemainingMs != null }.sleepTimerRemainingMs)
            vm.onIntent(PlayerIntent.CancelSleepTimer); assertNull(awaitUntil { it.sleepTimerRemainingMs == null }.sleepTimerRemainingMs)
        }
        assertEquals(listOf("sleep:900000", "sleep:cancel"), port.calls)
    }

    @Test
    fun `a queued episode can be played or removed`() = runTest {
        val ids = seed()
        queue.enqueue(ids[1]); queue.enqueue(ids[2])
        val vm = vm()
        vm.uiState.test {
            awaitUntil { it.upNext.size == 2 }
            vm.onIntent(PlayerIntent.RemoveFromQueue(ids[1]))
            awaitUntil { it.upNext.size == 1 }
            vm.onIntent(PlayerIntent.PlayQueued(ids[2]))
            testScheduler.advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf("play:${ids[2]}"), port.calls)
        assertEquals(listOf(ids[2]), queue.items.value)
    }
}
