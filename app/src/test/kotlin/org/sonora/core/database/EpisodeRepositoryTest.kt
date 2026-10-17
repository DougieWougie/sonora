// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.database

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.sonora.core.model.DownloadState
import org.sonora.core.model.SyncActionType

class EpisodeRepositoryTest {
    private val podcastDao = FakePodcastDao()
    private val episodeDao = FakeEpisodeDao()
    private val queue = FakeSyncQueueDao()
    private val clock = MutableClock()
    private val repo = EpisodeRepository(podcastDao, episodeDao, queue, FakeTransactionRunner(), clock)

    private suspend fun seedEpisode(duration: Long? = 600): Long {
        val podcastId = podcastDao.insert(PodcastEntity(feedUrl = "https://f/feed", title = "Show"))
        episodeDao.insertAll(
            listOf(
                EpisodeEntity(
                    podcastId = podcastId, guid = "g1", title = "Ep", enclosureUrl = "https://cdn/g1.mp3",
                    durationSeconds = duration,
                ),
            ),
        )
        return episodeDao.getForPodcast(podcastId).single().id
    }

    @Test
    fun `recordPlayback stores position and queues a PLAY action with UTC timestamp`() = runTest {
        val id = seedEpisode()
        clock.nowMillis = 1_800_000_000_000

        repo.recordPlayback(id, startedSeconds = 10, positionSeconds = 95)

        assertEquals(95, episodeDao.findById(id)!!.playbackPositionSeconds)
        val action = queue.getAll().single()
        assertEquals(SyncActionType.PLAY, action.type)
        assertEquals("https://f/feed", action.feedUrl)
        assertEquals("https://cdn/g1.mp3", action.episodeUrl)
        assertEquals("g1", action.guid)
        assertEquals(1_800_000_000_000, action.timestampUtcMillis)
        assertEquals(10L, action.startedSeconds)
        assertEquals(95L, action.positionSeconds)
        assertEquals(600L, action.totalSeconds)
    }

    @Test
    fun `repeated position updates coalesce into the latest pending PLAY`() = runTest {
        val id = seedEpisode()
        repo.recordPlayback(id, 0, 30)
        clock.nowMillis += 1000
        repo.recordPlayback(id, 0, 60)
        clock.nowMillis += 1000
        repo.recordPlayback(id, 0, 90)

        val action = queue.getAll().single()
        assertEquals(90L, action.positionSeconds)
        assertEquals(clock.nowMillis, action.timestampUtcMillis)
    }

    @Test
    fun `playback reaching the end marks the episode played`() = runTest {
        val id = seedEpisode(duration = 600)
        repo.recordPlayback(id, 0, 600)
        assertTrue(episodeDao.findById(id)!!.played)
    }

    @Test
    fun `playback mid episode does not mark it played`() = runTest {
        val id = seedEpisode(duration = 600)
        repo.recordPlayback(id, 0, 599 - 100)
        assertFalse(episodeDao.findById(id)!!.played)
    }

    @Test
    fun `markPlayed queues PLAY at the end of the episode`() = runTest {
        val id = seedEpisode(duration = 600)
        repo.markPlayed(id)

        assertTrue(episodeDao.findById(id)!!.played)
        val action = queue.getAll().single()
        assertEquals(SyncActionType.PLAY, action.type)
        assertEquals(600L, action.positionSeconds)
        assertEquals(600L, action.totalSeconds)
    }

    @Test
    fun `markNew resets state and queues NEW`() = runTest {
        val id = seedEpisode()
        repo.recordPlayback(id, 0, 100)
        repo.markNew(id)

        val ep = episodeDao.findById(id)!!
        assertEquals(0, ep.playbackPositionSeconds)
        assertFalse(ep.played)
        assertEquals(SyncActionType.NEW, queue.getAll().last().type)
        assertNull(queue.getAll().firstOrNull { it.type == SyncActionType.PLAY })
    }

    @Test
    fun `download and delete track offline state and queue actions`() = runTest {
        val id = seedEpisode()

        repo.markDownloaded(id, "/data/g1.mp3")
        var ep = episodeDao.findById(id)!!
        assertEquals(DownloadState.DOWNLOADED, ep.downloadState)
        assertEquals("/data/g1.mp3", ep.downloadPath)

        repo.deleteDownload(id)
        ep = episodeDao.findById(id)!!
        assertEquals(DownloadState.NONE, ep.downloadState)
        assertNull(ep.downloadPath)

        assertEquals(listOf(SyncActionType.DOWNLOAD, SyncActionType.DELETE), queue.getAll().map { it.type })
    }

    @Test
    fun `unknown episode is rejected without queueing anything`() = runTest {
        assertThrows<NoSuchElementException> { repo.recordPlayback(42, 0, 10) }
        assertTrue(queue.getAll().isEmpty())
    }

    @Test
    fun `observeEpisodes emits updates`() = runTest {
        val id = seedEpisode()
        val podcastId = episodeDao.findById(id)!!.podcastId

        repo.observeEpisodes(podcastId).test {
            assertEquals(0, awaitItem().single().playbackPositionSeconds)
            repo.recordPlayback(id, 0, 42)
            assertEquals(42, awaitItem().single().playbackPositionSeconds)
        }
    }

    @Test
    fun `savePosition updates only the position and does not queue a sync action`() = runTest {
        val id = seedEpisode()

        repo.savePosition(id, positionSeconds = 33)

        assertEquals(33, episodeDao.findById(id)!!.playbackPositionSeconds)
        assertFalse(episodeDao.findById(id)!!.played)
        assertTrue(queue.getAll().isEmpty())
    }
}
