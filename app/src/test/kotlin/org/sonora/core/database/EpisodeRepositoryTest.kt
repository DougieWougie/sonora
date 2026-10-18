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

    // --- marking older episodes -----------------------------------------------------------------

    private class Series(val podcastId: Long, val ids: Map<String, Long>)

    /** Show A: a1 (100), a2 (200, already played), a3 (300), a4 (400), undated; show B: b1 (50). */
    private suspend fun seedSeries(): Series {
        val a = podcastDao.insert(PodcastEntity(feedUrl = "https://a/feed", title = "A"))
        val b = podcastDao.insert(PodcastEntity(feedUrl = "https://b/feed", title = "B"))
        fun ep(podcast: Long, guid: String, published: Long?, played: Boolean = false) = EpisodeEntity(
            podcastId = podcast, guid = guid, title = guid, enclosureUrl = "https://cdn/$guid.mp3",
            durationSeconds = 600, publishedAtMillis = published, played = played,
        )
        episodeDao.insertAll(
            listOf(
                ep(a, "a1", 100), ep(a, "a2", 200, played = true), ep(a, "a3", 300), ep(a, "a4", 400),
                ep(a, "undated", null), ep(b, "b1", 50),
            ),
        )
        return Series(a, episodeDao.rows.value.associate { it.guid to it.id })
    }

    private fun Series.played() = episodeDao.rows.value.filter { it.played }.map { it.guid }.toSet()

    @Test
    fun `markOlderPlayed marks only older unplayed episodes of the same podcast`() = runTest {
        val series = seedSeries()

        val marked = repo.markOlderPlayed(series.ids.getValue("a3"))

        assertEquals(listOf(series.ids.getValue("a1")), marked)
        assertEquals(setOf("a1", "a2"), series.played())
        assertEquals(600L, episodeDao.findById(series.ids.getValue("a1"))!!.playbackPositionSeconds)
    }

    @Test
    fun `markOlderPlayed queues one PLAY action per episode it changed`() = runTest {
        val series = seedSeries()

        repo.markOlderPlayed(series.ids.getValue("a4"))

        val actions = queue.getAll()
        assertEquals(setOf("https://cdn/a1.mp3", "https://cdn/a3.mp3"), actions.map { it.episodeUrl }.toSet())
        assertEquals(2, actions.size)
        assertTrue(actions.all { it.type == SyncActionType.PLAY && it.positionSeconds == 600L })
    }

    @Test
    fun `markOlderPlayed on the oldest episode changes nothing`() = runTest {
        val series = seedSeries()

        val marked = repo.markOlderPlayed(series.ids.getValue("a1"))

        assertEquals(emptyList<Long>(), marked)
        assertEquals(setOf("a2"), series.played())
        assertTrue(queue.getAll().isEmpty())
    }

    @Test
    fun `markOlderPlayed does nothing for an episode without a publish date`() = runTest {
        val series = seedSeries()

        assertEquals(emptyList<Long>(), repo.markOlderPlayed(series.ids.getValue("undated")))
        assertEquals(setOf("a2"), series.played())
    }

    @Test
    fun `countOlderUnplayed counts what markOlderPlayed would change and changes nothing`() = runTest {
        val series = seedSeries()

        assertEquals(1, repo.countOlderUnplayed(series.ids.getValue("a3")))
        assertEquals(2, repo.countOlderUnplayed(series.ids.getValue("a4")))
        assertEquals(0, repo.countOlderUnplayed(series.ids.getValue("a1")))
        assertEquals(setOf("a2"), series.played())
        assertTrue(queue.getAll().isEmpty())
    }
}
