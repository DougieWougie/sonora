// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.sync

import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.sonora.core.database.EpisodeEntity
import org.sonora.core.database.FakeEpisodeDao
import org.sonora.core.database.FakePodcastDao
import org.sonora.core.database.FakeTransactionRunner
import org.sonora.core.database.MutableClock
import org.sonora.core.database.PodcastEntity
import org.sonora.core.database.PodcastRepository
import org.sonora.core.model.ParsedEpisode
import org.sonora.core.model.ParsedFeed
import org.xmlpull.v1.XmlPullParserException
import retrofit2.HttpException
import retrofit2.Response

class PodcastSyncPipelineTest {
    private val podcastDao = FakePodcastDao()
    private val episodeDao = FakeEpisodeDao()
    private val events = mutableListOf<String>()

    private val scheduled = mutableListOf<Pair<List<Long>, Boolean>>()
    private var unmeteredOnly = true

    private var remote: suspend () -> SyncReport? = { SyncReport() }
    private val feeds = mutableMapOf<String, () -> ParsedFeed>()
    private var fetchDelayMs = 0L
    private var inFlight = 0
    private var maxInFlight = 0

    private var retention: suspend () -> Unit = { events += "retention" }

    private fun pipeline(concurrency: Int = 4, maxAuto: Int = 3) = PodcastSyncPipeline(
        remoteSync = RemoteSync {
            events += "remote"
            remote()
        },
        podcastDao = podcastDao,
        episodeDao = episodeDao,
        podcastRepository = PodcastRepository(podcastDao, episodeDao, FakeTransactionRunner(), MutableClock()),
        feedFetcher = FeedFetcher { url ->
            events += "fetch:$url"
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            try {
                if (fetchDelayMs > 0) delay(fetchDelayMs)
                (feeds[url] ?: error("no feed for $url"))()
            } finally {
                inFlight--
            }
        },
        downloads = DownloadScheduler { ids, unmetered ->
            events += "schedule"
            scheduled += ids to unmetered
        },
        unmeteredOnlyForAutoDownload = { unmeteredOnly },
        enforceRetention = { retention() },
        maxConcurrentRefreshes = concurrency,
        maxAutoDownloadsPerPodcast = maxAuto,
    )

    private fun episode(guid: String, published: Long) = ParsedEpisode(
        guid, "Ep $guid", null, "https://cdn/$guid.mp3", "audio/mpeg", null, 60, published, null,
    )

    private fun feed(vararg episodes: ParsedEpisode) = ParsedFeed("Show", null, null, null, null, episodes.toList())

    private suspend fun subscribe(url: String, autoDownload: Boolean = false, existing: List<String> = emptyList()): Long {
        val id = podcastDao.insert(PodcastEntity(feedUrl = url, title = url, autoDownload = autoDownload))
        episodeDao.insertAll(
            existing.map { EpisodeEntity(podcastId = id, guid = it, title = it, enclosureUrl = "https://cdn/$it.mp3") },
        )
        return id
    }

    // --- ordering & resilience ---------------------------------------------------------------

    @Test
    fun `runs remote sync first, then feed refreshes, then schedules auto-downloads`() = runTest {
        subscribe("https://a", autoDownload = true, existing = listOf("old"))
        feeds["https://a"] = { feed(episode("old", 1), episode("new", 2)) }

        pipeline().run()

        assertEquals(listOf("remote", "fetch:https://a", "schedule", "retention"), events)
    }

    @Test
    fun `a failing retention pass does not fail the sync`() = runTest {
        subscribe("https://a")
        feeds["https://a"] = { feed(episode("a", 1)) }
        retention = { error("disk gone") }

        val outcome = pipeline().run()

        assertEquals(1, outcome.refreshedFeeds)
        assertEquals(WorkOutcome.SUCCESS, outcome.toWorkOutcome(attempt = 1))
    }

    @Test
    fun `a dead feed does not abort the others or the remote sync`() = runTest {
        subscribe("https://good1")
        subscribe("https://dead")
        subscribe("https://good2")
        feeds["https://good1"] = { feed(episode("a", 1)) }
        feeds["https://good2"] = { feed(episode("b", 1)) }
        feeds["https://dead"] = { throw XmlPullParserException("not xml") }

        val outcome = pipeline().run()

        assertEquals(1, events.count { it == "remote" })
        assertEquals(2, outcome.refreshedFeeds)
        assertEquals(mapOf("https://dead" to FailureKind.PERMANENT), outcome.failedFeeds)
        assertEquals(WorkOutcome.SUCCESS, outcome.toWorkOutcome(attempt = 0))
        assertEquals("Show", podcastDao.findByFeedUrl("https://good1")!!.title)
    }

    @Test
    fun `a transient remote failure still refreshes feeds and asks for a retry`() = runTest {
        subscribe("https://a")
        feeds["https://a"] = { feed(episode("a", 1)) }
        remote = { throw IOException("offline") }

        val outcome = pipeline().run()

        assertEquals(1, outcome.refreshedFeeds)
        assertEquals(RemoteStatus.Failed(FailureKind.TRANSIENT), outcome.remote.withoutCause())
        assertEquals(WorkOutcome.RETRY, outcome.toWorkOutcome(attempt = 0))
    }

    @Test
    fun `server errors from the sync backend are transient`() = runTest {
        remote = { throw SyncException("busy", HttpException(Response.error<Any>(503, "".toResponseBody()))) }

        assertEquals(WorkOutcome.RETRY, pipeline().run().toWorkOutcome(attempt = 0))
    }

    @Test
    fun `rejected credentials fail instead of retrying, but feeds are still refreshed`() = runTest {
        subscribe("https://a")
        feeds["https://a"] = { feed(episode("a", 1)) }
        remote = { throw SyncAuthException("401") }

        val outcome = pipeline().run()

        assertEquals(1, outcome.refreshedFeeds)
        assertEquals(WorkOutcome.FAILURE, outcome.toWorkOutcome(attempt = 0))
    }

    @Test
    fun `an unconfigured remote is skipped and counts as success`() = runTest {
        remote = { null }

        val outcome = pipeline().run()

        assertEquals(RemoteStatus.NotConfigured, outcome.remote)
        assertEquals(WorkOutcome.SUCCESS, outcome.toWorkOutcome(attempt = 0))
    }

    @Test
    fun `retries when every feed fails for connectivity reasons`() = runTest {
        subscribe("https://a")
        subscribe("https://b")
        feeds["https://a"] = { throw IOException("offline") }
        feeds["https://b"] = { throw IOException("offline") }

        assertEquals(WorkOutcome.RETRY, pipeline().run().toWorkOutcome(attempt = 0))
    }

    @Test
    fun `a single flaky feed among healthy ones is not worth a retry`() = runTest {
        subscribe("https://a")
        subscribe("https://b")
        feeds["https://a"] = { throw IOException("timeout") }
        feeds["https://b"] = { feed(episode("b", 1)) }

        val outcome = pipeline().run()

        assertEquals(mapOf("https://a" to FailureKind.TRANSIENT), outcome.failedFeeds)
        assertEquals(WorkOutcome.SUCCESS, outcome.toWorkOutcome(attempt = 0))
    }

    @Test
    fun `retries are capped`() = runTest {
        remote = { throw IOException("offline") }
        val outcome = pipeline().run()

        assertEquals(WorkOutcome.RETRY, outcome.toWorkOutcome(attempt = 3, maxAttempts = 5))
        assertEquals(WorkOutcome.FAILURE, outcome.toWorkOutcome(attempt = 5, maxAttempts = 5))
    }

    @Test
    fun `refreshes feeds in parallel but never beyond the concurrency bound`() = runTest {
        repeat(6) { i ->
            subscribe("https://f$i")
            feeds["https://f$i"] = { feed(episode("e$i", 1)) }
        }
        fetchDelayMs = 100

        pipeline(concurrency = 2).run()

        assertEquals(2, maxInFlight)
        assertEquals(300L, testScheduler.currentTime) // 6 feeds / 2 at a time * 100 ms
    }

    // --- auto-download -----------------------------------------------------------------------

    @Test
    fun `schedules only new episodes of auto-download podcasts, newest first`() = runTest {
        val auto = subscribe("https://auto", autoDownload = true, existing = listOf("old"))
        subscribe("https://manual", autoDownload = false, existing = listOf("m-old"))
        feeds["https://auto"] = { feed(episode("old", 1), episode("n1", 2), episode("n2", 3)) }
        feeds["https://manual"] = { feed(episode("m-old", 1), episode("m-new", 2)) }

        val outcome = pipeline().run()

        val newest = episodeDao.getForPodcast(auto).filter { it.guid in setOf("n1", "n2") }
            .sortedByDescending { it.publishedAtMillis }.map { it.id }
        assertEquals(listOf(newest to true), scheduled)
        assertEquals(2, outcome.queuedDownloads)
    }

    @Test
    fun `does not download the back catalogue on a podcast's first refresh`() = runTest {
        subscribe("https://fresh", autoDownload = true)
        feeds["https://fresh"] = { feed(episode("a", 1), episode("b", 2)) }

        val outcome = pipeline().run()

        assertTrue(scheduled.isEmpty())
        assertEquals(0, outcome.queuedDownloads)
    }

    @Test
    fun `caps auto-downloads per podcast to the newest few`() = runTest {
        subscribe("https://busy", autoDownload = true, existing = listOf("old"))
        feeds["https://busy"] = { feed(*(1..5).map { episode("n$it", it.toLong() + 10) }.toTypedArray()) }

        pipeline(maxAuto = 2).run()

        val ids = scheduled.single().first
        assertEquals(setOf("n5", "n4"), ids.map { episodeDao.findById(it)!!.guid }.toSet())
    }

    @Test
    fun `passes the unmetered-only preference through to the scheduler`() = runTest {
        unmeteredOnly = false
        subscribe("https://auto", autoDownload = true, existing = listOf("old"))
        feeds["https://auto"] = { feed(episode("old", 1), episode("new", 2)) }

        pipeline().run()

        assertEquals(false, scheduled.single().second)
    }

    @Test
    fun `a podcast whose refresh failed schedules nothing`() = runTest {
        subscribe("https://auto", autoDownload = true, existing = listOf("old"))
        feeds["https://auto"] = { throw IOException("offline") }

        pipeline().run()

        assertTrue(scheduled.isEmpty())
        assertTrue("schedule" !in events)
    }

    private fun RemoteStatus.withoutCause(): RemoteStatus =
        if (this is RemoteStatus.Failed) RemoteStatus.Failed(kind) else this
}
