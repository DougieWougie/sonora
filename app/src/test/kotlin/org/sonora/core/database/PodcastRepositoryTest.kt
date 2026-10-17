// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.database

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.sonora.core.model.DownloadState
import org.sonora.core.model.ParsedEpisode
import org.sonora.core.model.ParsedFeed

class PodcastRepositoryTest {
    private val podcastDao = FakePodcastDao()
    private val episodeDao = FakeEpisodeDao()
    private val tx = FakeTransactionRunner()
    private val clock = MutableClock()
    private val repo = PodcastRepository(podcastDao, episodeDao, tx, clock)

    private fun ep(guid: String, title: String = "T-$guid", duration: Long? = 600) = ParsedEpisode(
        guid = guid, title = title, description = null, enclosureUrl = "https://cdn/$guid.mp3",
        mimeType = "audio/mpeg", sizeBytes = 1, durationSeconds = duration, publishedAtMillis = 1000, imageUrl = null,
    )

    private fun feed(title: String = "Show", vararg eps: ParsedEpisode) =
        ParsedFeed(title, "desc", "https://site", "Author", "https://img", eps.toList())

    @Test
    fun `first refresh inserts podcast and episodes`() = runTest {
        val id = repo.refresh("https://f/feed", feed("Show", ep("a"), ep("b")))

        val podcast = podcastDao.findById(id)!!
        assertEquals("https://f/feed", podcast.feedUrl)
        assertEquals("Show", podcast.title)
        assertEquals(clock.nowMillis, podcast.lastRefreshedAtMillis)
        assertEquals(setOf("a", "b"), episodeDao.getForPodcast(id).map { it.guid }.toSet())
    }

    @Test
    fun `second refresh updates metadata and adds new episodes without duplicating`() = runTest {
        val id = repo.refresh("https://f/feed", feed("Old", ep("a")))
        clock.nowMillis += 5_000

        val id2 = repo.refresh("https://f/feed", feed("New", ep("a", title = "A renamed"), ep("c")))

        assertEquals(id, id2)
        assertEquals(1, podcastDao.getAll().size)
        assertEquals("New", podcastDao.findById(id)!!.title)
        assertEquals(clock.nowMillis, podcastDao.findById(id)!!.lastRefreshedAtMillis)
        val episodes = episodeDao.getForPodcast(id).associateBy { it.guid }
        assertEquals(setOf("a", "c"), episodes.keys)
        assertEquals("A renamed", episodes.getValue("a").title)
    }

    @Test
    fun `refresh preserves local playback and download state`() = runTest {
        val id = repo.refresh("https://f/feed", feed("Show", ep("a")))
        val local = episodeDao.getForPodcast(id).single().copy(
            playbackPositionSeconds = 321, played = true,
            downloadState = DownloadState.DOWNLOADED, downloadPath = "/data/a.mp3",
        )
        episodeDao.update(local)

        repo.refresh("https://f/feed", feed("Show", ep("a", title = "Changed")))

        val after = episodeDao.getForPodcast(id).single()
        assertEquals("Changed", after.title)
        assertEquals(local.id, after.id)
        assertEquals(321, after.playbackPositionSeconds)
        assertTrue(after.played)
        assertEquals(DownloadState.DOWNLOADED, after.downloadState)
        assertEquals("/data/a.mp3", after.downloadPath)
    }

    @Test
    fun `episodes that disappear from the feed are retained`() = runTest {
        val id = repo.refresh("https://f/feed", feed("Show", ep("a"), ep("b")))

        repo.refresh("https://f/feed", feed("Show", ep("b")))

        assertEquals(setOf("a", "b"), episodeDao.getForPodcast(id).map { it.guid }.toSet())
    }

    @Test
    fun `refresh runs inside one transaction`() = runTest {
        repo.refresh("https://f/feed", feed("Show", ep("a")))
        assertEquals(1, tx.runs)
    }
}
