// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.media

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.sonora.core.database.EpisodeEntity
import org.sonora.core.database.EpisodeRepository
import org.sonora.core.database.FakeEpisodeDao
import org.sonora.core.database.FakePodcastDao
import org.sonora.core.database.FakeSyncQueueDao
import org.sonora.core.database.FakeTransactionRunner
import org.sonora.core.database.MutableClock
import org.sonora.core.database.PodcastEntity

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackHubTest {
    private val podcastDao = FakePodcastDao()
    private val episodeDao = FakeEpisodeDao()
    private val engine = FakePlayerEngine()
    private val repo = EpisodeRepository(podcastDao, episodeDao, FakeSyncQueueDao(), FakeTransactionRunner(), MutableClock())

    private fun TestScope.manager() = PlaybackStateManager(
        engine = engine, episodeDao = episodeDao, podcastDao = podcastDao, episodeRepository = repo,
        fileExists = { false }, scope = backgroundScope,
    )

    private suspend fun seedEpisode(): Long {
        val podcastId = podcastDao.insert(PodcastEntity(feedUrl = "https://f/feed", title = "Show"))
        episodeDao.insertAll(listOf(EpisodeEntity(podcastId = podcastId, guid = "g", title = "Ep", enclosureUrl = "https://cdn/g.mp3", durationSeconds = 600)))
        return episodeDao.getForPodcast(podcastId).single().id
    }

    @Test
    fun `reports an idle state before a manager is attached`() = runTest {
        PlaybackHub(timeoutMs = 1_000).state.test {
            assertEquals(PlaybackState(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `mirrors the attached manager's state and commands drive it`() = runTest {
        val hub = PlaybackHub(timeoutMs = 1_000)
        val id = seedEpisode()
        hub.attach(manager())

        hub.state.test {
            assertEquals(null, awaitItem().episodeId)
            hub.play(id)
            assertEquals(id, awaitItem().episodeId)
            hub.setSpeed(1.5f)
            assertEquals(1.5f, expectMostRecentItem().speed)
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue("play" in engine.calls)
    }

    @Test
    fun `a command issued before the service attaches waits for it`() = runTest {
        val hub = PlaybackHub(timeoutMs = 5_000)
        val id = seedEpisode()

        val pending = launch { hub.play(id) }
        runCurrent()
        assertTrue(engine.calls.isEmpty())
        hub.attach(manager())
        runCurrent()

        pending.join()
        assertEquals(listOf("prepare", "play"), engine.calls)
    }

    @Test
    fun `a command times out when no service ever attaches`() = runTest {
        val hub = PlaybackHub(timeoutMs = 2_000)
        assertThrows<TimeoutCancellationException> { hub.play(1) }
    }

    @Test
    fun `detaching returns the state to idle`() = runTest {
        val hub = PlaybackHub(timeoutMs = 1_000)
        val m = manager()
        hub.attach(m)
        hub.setSpeed(2f)

        hub.state.test {
            assertEquals(2f, awaitItem().speed)
            hub.detach(m)
            assertEquals(PlaybackState(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
