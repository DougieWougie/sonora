// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.download

import org.sonora.testutil.showId
import org.sonora.testutil.episodeEntity
import app.cash.turbine.test
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.sonora.testutil.MockWebServerExtension
import org.sonora.core.database.EpisodeRepository
import org.sonora.core.database.FakeEpisodeDao
import org.sonora.core.database.FakePodcastDao
import org.sonora.core.database.FakeSyncQueueDao
import org.sonora.core.database.FakeTransactionRunner
import org.sonora.core.database.MutableClock
import org.sonora.core.model.DownloadState
import org.sonora.core.model.SyncActionType

class EpisodeDownloadManagerTest {
    @JvmField @RegisterExtension val web = MockWebServerExtension()
    private val server get() = web.server
    private val podcastDao = FakePodcastDao()
    private val episodeDao = FakeEpisodeDao()
    private val queue = FakeSyncQueueDao()
    private val repo = EpisodeRepository(podcastDao, episodeDao, queue, FakeTransactionRunner(), MutableClock())
    private val payload = ByteArray(300_000) { (it % 251).toByte() }
    private var policy = DownloadPolicy()
    private val notices = org.sonora.testutil.FakeEvictionNotices()

    @TempDir lateinit var dir: File

    private lateinit var manager: EpisodeDownloadManager

    @BeforeEach
    fun setUp() {
        manager = EpisodeDownloadManager(
            episodeDao = episodeDao,
            episodeRepository = repo,
            client = OkHttpClient(),
            downloadsDir = dir,
            policy = { policy },
            ioDispatcher = Dispatchers.IO,
            allowCleartext = true,
            progressStepBytes = 64 * 1024,
            notices = notices,
        )
    }


    private suspend fun seed(
        guid: String = "g1",
        path: String = "/a/$guid.mp3",
        state: DownloadState = DownloadState.NONE,
        played: Boolean = false,
        published: Long? = null,
        localPath: String? = null,
    ): Long {
        val podcastId = podcastDao.showId()
        episodeDao.insertAll(
            listOf(
                episodeEntity(
                    podcastId, guid, enclosureUrl = server.url(path).toString(), sizeBytes = payload.size.toLong(),
                    durationSeconds = 600, downloadState = state, downloadPath = localPath,
                    played = played, publishedAtMillis = published,
                ),
            ),
        )
        return episodeDao.getForPodcast(podcastId).first { it.guid == guid }.id
    }

    private fun ok(body: ByteArray = payload) =
        MockResponse.Builder().body(Buffer().write(body)).build()

    private fun partFile(id: Long) = File(dir, "episode-$id.mp3.part")
    private fun finalFile(id: Long) = File(dir, "episode-$id.mp3")

    // --- streaming download ------------------------------------------------------------------

    @Test
    fun `streams the enclosure to disk, emitting monotonic progress`() = runTest {
        val id = seed()
        server.enqueue(ok())

        manager.download(id).test {
            val items = mutableListOf<DownloadProgress>()
            while (true) {
                val event = awaitEvent()
                if (event is app.cash.turbine.Event.Item) items += event.value else break
            }
            assertTrue(items.size >= 3, "expected several progress steps, got ${items.size}")
            assertEquals(items.map { it.bytesDownloaded }.sorted(), items.map { it.bytesDownloaded })
            assertEquals(DownloadProgress(300_000, 300_000), items.last())
        }
        assertArrayEquals(payload, finalFile(id).readBytes())
        assertFalse(partFile(id).exists())
    }

    @Test
    fun `marks the episode DOWNLOADING while bytes are in flight`() = runTest {
        val id = seed()
        // Throttled so the transfer is still running when the first progress item is observed.
        server.enqueue(
            MockResponse.Builder().body(Buffer().write(payload))
                .throttleBody(64 * 1024, 100, java.util.concurrent.TimeUnit.MILLISECONDS).build(),
        )

        manager.download(id).test(timeout = kotlin.time.Duration.parse("10s")) {
            awaitItem()
            assertEquals(DownloadState.DOWNLOADING, episodeDao.findById(id)!!.downloadState)
            assertTrue(episodeDao.findById(id)!!.downloadedBytes > 0)
            while (awaitEvent() is app.cash.turbine.Event.Item) Unit
        }
        assertEquals(DownloadState.DOWNLOADED, episodeDao.findById(id)!!.downloadState)
    }

    @Test
    fun `completion stores the local path and queues a DOWNLOAD action`() = runTest {
        val id = seed()
        server.enqueue(ok())

        manager.downloadToCompletion(id)

        val episode = episodeDao.findById(id)!!
        assertEquals(DownloadState.DOWNLOADED, episode.downloadState)
        assertEquals(finalFile(id).absolutePath, episode.downloadPath)
        assertEquals(300_000L, episode.downloadedBytes)
        val action = queue.getAll().single()
        assertEquals(SyncActionType.DOWNLOAD, action.type)
        assertEquals(episode.enclosureUrl, action.episodeUrl)
    }

    @Test
    fun `an already downloaded episode is not fetched again`() = runTest {
        val id = seed()
        server.enqueue(ok())
        manager.downloadToCompletion(id)

        manager.downloadToCompletion(id)

        assertEquals(1, server.requestCount)
        assertEquals(1, queue.getAll().count { it.type == SyncActionType.DOWNLOAD })
    }

    @Test
    fun `downloaded state with a missing file is downloaded again`() = runTest {
        val id = seed(state = DownloadState.DOWNLOADED, localPath = File(dir, "gone.mp3").absolutePath)
        server.enqueue(ok())

        manager.downloadToCompletion(id)

        assertEquals(1, server.requestCount)
        assertTrue(finalFile(id).exists())
    }

    // --- resume / retry ----------------------------------------------------------------------

    @Test
    fun `resumes a partial file with a Range header and appends the remainder`() = runTest {
        val id = seed()
        partFile(id).writeBytes(payload.copyOfRange(0, 100_000))
        server.enqueue(
            MockResponse.Builder().code(206)
                .addHeader("Content-Range", "bytes 100000-299999/300000")
                .body(Buffer().write(payload.copyOfRange(100_000, 300_000))).build(),
        )

        manager.downloadToCompletion(id)

        assertEquals("bytes=100000-", server.takeRequest().headers["Range"])
        assertArrayEquals(payload, finalFile(id).readBytes())
    }

    @Test
    fun `restarts cleanly when the server ignores the Range header`() = runTest {
        val id = seed()
        partFile(id).writeBytes(payload.copyOfRange(0, 100_000))
        server.enqueue(ok())

        manager.downloadToCompletion(id)

        assertArrayEquals(payload, finalFile(id).readBytes())
    }

    @Test
    fun `restarts from scratch when the partial file is rejected with 416`() = runTest {
        val id = seed()
        partFile(id).writeBytes(ByteArray(400_000))
        server.enqueue(MockResponse.Builder().code(416).build())
        server.enqueue(ok())

        manager.downloadToCompletion(id)

        assertEquals("bytes=400000-", server.takeRequest().headers["Range"])
        assertNull(server.takeRequest().headers["Range"])
        assertArrayEquals(payload, finalFile(id).readBytes())
    }

    @Test
    fun `a dropped connection marks the episode FAILED, keeps the partial file and a retry completes it`() = runTest {
        val id = seed()
        server.enqueue(
            MockResponse.Builder().body(Buffer().write(payload))
                .onResponseBody(SocketEffect.CloseSocket()).build(),
        )

        val failure = assertThrows<DownloadException> { manager.downloadToCompletion(id) }

        assertTrue(failure.retryable)
        assertEquals(DownloadState.FAILED, episodeDao.findById(id)!!.downloadState)
        assertTrue(queue.getAll().isEmpty())
        val partial = partFile(id).length()
        assertTrue(partial < payload.size)

        server.enqueue(
            if (partial > 0) {
                MockResponse.Builder().code(206)
                    .addHeader("Content-Range", "bytes $partial-299999/300000")
                    .body(Buffer().write(payload.copyOfRange(partial.toInt(), payload.size))).build()
            } else {
                ok()
            },
        )
        manager.downloadToCompletion(id)

        server.takeRequest()
        assertArrayEquals(payload, finalFile(id).readBytes())
        assertEquals(DownloadState.DOWNLOADED, episodeDao.findById(id)!!.downloadState)
    }

    @Test
    fun `client errors fail permanently, server errors are retryable`() = runTest {
        val notFound = seed(guid = "gone", path = "/gone.mp3")
        val busy = seed(guid = "busy", path = "/busy.mp3")
        server.enqueue(MockResponse.Builder().code(404).build())
        server.enqueue(MockResponse.Builder().code(503).build())

        val permanent = assertThrows<DownloadException> { manager.downloadToCompletion(notFound) }
        val transient = assertThrows<DownloadException> { manager.downloadToCompletion(busy) }

        assertFalse(permanent.retryable)
        assertTrue(transient.retryable)
        assertEquals(DownloadState.FAILED, episodeDao.findById(notFound)!!.downloadState)
        assertFalse(partFile(notFound).exists())
    }

    @Test
    fun `requests the https form of a cleartext url unless cleartext is allowed`() = runTest {
        val id = seed()
        val requested = mutableListOf<String>()
        val recording = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requested += chain.request().url.toString()
                throw java.io.IOException("stop here")
            }
            .build()
        val strict = EpisodeDownloadManager(
            episodeDao, repo, recording, dir, { policy }, Dispatchers.IO, allowCleartext = false,
        )

        assertThrows<DownloadException> { strict.downloadToCompletion(id) }

        assertEquals(1, requested.size)
        assertTrue(requested.single().startsWith("https://"), requested.single())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `refuses non-http urls even when cleartext is allowed`() = runTest {
        val id = seed()
        episodeDao.update(episodeDao.findById(id)!!.copy(enclosureUrl = "ftp://cdn/a.mp3"))

        val failure = assertThrows<DownloadException> { manager.downloadToCompletion(id) }

        assertFalse(failure.retryable)
        assertEquals(0, server.requestCount)
    }

    // --- queueing ----------------------------------------------------------------------------

    @Test
    fun `enqueue marks NONE and FAILED episodes QUEUED but leaves downloaded ones alone`() = runTest {
        val fresh = seed(guid = "a")
        val failed = seed(guid = "b", state = DownloadState.FAILED)
        val done = seed(guid = "c", state = DownloadState.DOWNLOADED, localPath = "/x")

        assertTrue(manager.enqueue(fresh))
        assertTrue(manager.enqueue(failed))
        assertFalse(manager.enqueue(done))

        assertEquals(DownloadState.QUEUED, episodeDao.findById(fresh)!!.downloadState)
        assertEquals(DownloadState.QUEUED, episodeDao.findById(failed)!!.downloadState)
        assertEquals(DownloadState.DOWNLOADED, episodeDao.findById(done)!!.downloadState)
    }

    // --- cleanup -----------------------------------------------------------------------------

    private suspend fun downloaded(guid: String, published: Long, played: Boolean = false, size: Int = 1000): Long {
        val id = seed(guid = guid, path = "/$guid.mp3", published = published, played = played)
        val file = File(dir, "episode-$id.mp3").apply { writeBytes(ByteArray(size)) }
        repo.markDownloaded(id, file.absolutePath)
        queue.deleteByIds(queue.getAll().map { it.id })
        return id
    }

    @Test
    fun `marking an episode played deletes the file and queues a DELETE action`() = runTest {
        val id = downloaded("g1", published = 1)
        val file = finalFile(id)

        manager.markPlayed(id)

        assertFalse(file.exists())
        val episode = episodeDao.findById(id)!!
        assertEquals(DownloadState.NONE, episode.downloadState)
        assertNull(episode.downloadPath)
        assertTrue(episode.played)
        val types = queue.getAll().map { it.type }
        assertTrue(SyncActionType.DELETE in types)
        assertTrue(SyncActionType.PLAY in types)
    }

    @Test
    fun `keeps the file when auto-delete is disabled`() = runTest {
        policy = DownloadPolicy(deleteWhenPlayed = false)
        val id = downloaded("g1", published = 1)

        manager.markPlayed(id)

        assertTrue(finalFile(id).exists())
        assertEquals(DownloadState.DOWNLOADED, episodeDao.findById(id)!!.downloadState)
        assertTrue(queue.getAll().none { it.type == SyncActionType.DELETE })
    }

    @Test
    fun `completing an episode that was never downloaded queues no DELETE`() = runTest {
        val id = seed()

        manager.onEpisodeCompleted(id)

        assertTrue(queue.getAll().isEmpty())
    }

    @Test
    fun `deleteDownload removes the file and any partial file and queues DELETE`() = runTest {
        val id = downloaded("g1", published = 1)
        partFile(id).writeBytes(ByteArray(10))

        manager.deleteDownload(id)

        assertFalse(finalFile(id).exists())
        assertFalse(partFile(id).exists())
        assertEquals(SyncActionType.DELETE, queue.getAll().single().type)
    }

    @Test
    fun `enforceRetention evicts played downloads first and then the oldest unplayed ones`() = runTest {
        policy = DownloadPolicy(maxStorageBytes = 1500)
        val oldPlayed = downloaded("old", published = 1, played = true)
        val newPlayed = downloaded("new", published = 3, played = true)
        val unplayed = downloaded("unplayed", published = 0, played = false)

        val evicted = manager.enforceRetention()

        assertEquals(listOf(oldPlayed, newPlayed), evicted)
        assertFalse(finalFile(oldPlayed).exists())
        assertFalse(finalFile(newPlayed).exists())
        assertTrue(finalFile(unplayed).exists())
        assertEquals(listOf(SyncActionType.DELETE, SyncActionType.DELETE), queue.getAll().map { it.type })
    }

    @Test
    fun `enforceRetention keeps only the newest downloads per podcast`() = runTest {
        policy = DownloadPolicy(keepPerPodcast = 1)
        val older = downloaded("older", published = 1)
        val newer = downloaded("newer", published = 2)

        assertEquals(listOf(older), manager.enforceRetention())
        assertEquals(DownloadState.NONE, episodeDao.findById(older)!!.downloadState)
        assertEquals(DownloadState.DOWNLOADED, episodeDao.findById(newer)!!.downloadState)
    }

    @Test
    fun `enforceRetention never evicts episodes in use`() = runTest {
        policy = DownloadPolicy(maxStorageBytes = 1)
        val playing = downloaded("playing", published = 1, played = true)
        val other = downloaded("other", published = 2)

        assertEquals(listOf(other), manager.enforceRetention(inUse = setOf(playing)))
        assertTrue(finalFile(playing).exists())
    }

    @Test
    fun `enforceRetention does nothing under an unlimited policy`() = runTest {
        downloaded("a", published = 1, played = true)

        assertEquals(emptyList<Long>(), manager.enforceRetention())
    }

    @Test
    fun `enforceRetention ignores downloads that are not complete`() = runTest {
        policy = DownloadPolicy(maxStorageBytes = 1)
        val queued = seed(guid = "queued", state = DownloadState.QUEUED, published = 1)

        assertEquals(emptyList<Long>(), manager.enforceRetention())
        assertEquals(DownloadState.QUEUED, episodeDao.findById(queued)!!.downloadState)
    }

    // --- cancelling & bulk delete ------------------------------------------------------------

    @Test
    fun `cancelling a queued download resets it and removes the partial file`() = runTest {
        val id = seed(state = DownloadState.QUEUED)
        partFile(id).writeBytes(ByteArray(10))

        manager.cancelDownload(id)

        assertEquals(DownloadState.NONE, episodeDao.findById(id)!!.downloadState)
        assertEquals(0L, episodeDao.findById(id)!!.downloadedBytes)
        assertFalse(partFile(id).exists())
    }

    @Test
    fun `cancelling a running download leaves it not downloaded rather than re-queued`() = runTest {
        val id = seed()
        server.enqueue(
            MockResponse.Builder().body(Buffer().write(payload))
                .throttleBody(64 * 1024, 100, java.util.concurrent.TimeUnit.MILLISECONDS).build(),
        )
        val progressed = kotlinx.coroutines.CompletableDeferred<Unit>()
        val job = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch {
            manager.download(id).collect { progressed.complete(Unit) }
        }
        progressed.await()

        manager.cancelDownload(id)
        job.cancelAndJoin()

        assertEquals(DownloadState.NONE, episodeDao.findById(id)!!.downloadState)
        assertEquals(0L, episodeDao.findById(id)!!.downloadedBytes)
        assertFalse(partFile(id).exists())
    }

    @Test
    fun `an old cancel does not stop a download requested later`() = runTest {
        val id = seed(state = DownloadState.QUEUED)
        manager.cancelDownload(id)
        server.enqueue(ok())

        manager.enqueue(id)
        manager.downloadToCompletion(id)

        assertEquals(DownloadState.DOWNLOADED, episodeDao.findById(id)!!.downloadState)
    }

    @Test
    fun `deleteAllPlayedDownloads removes played downloads only`() = runTest {
        val playedA = downloaded("a", published = 1, played = true)
        val playedB = downloaded("b", published = 2, played = true)
        val unplayed = downloaded("c", published = 3)
        val inFlight = seed(guid = "d", state = DownloadState.DOWNLOADING, played = true)

        val removed = manager.deleteAllPlayedDownloads()

        assertEquals(setOf(playedA, playedB), removed.toSet())
        assertFalse(finalFile(playedA).exists())
        assertTrue(finalFile(unplayed).exists())
        assertEquals(DownloadState.DOWNLOADING, episodeDao.findById(inFlight)!!.downloadState)
        assertEquals(2, queue.getAll().count { it.type == SyncActionType.DELETE })
    }

    @Test
    fun `marking older episodes played removes their downloads and leaves newer ones alone`() = runTest {
        val oldest = downloaded("a", published = 1)
        val older = downloaded("b", published = 2)
        val tapped = downloaded("c", published = 3)
        val newer = downloaded("d", published = 4)

        val count = manager.markOlderPlayed(tapped)

        assertEquals(2, count)
        assertFalse(finalFile(oldest).exists())
        assertFalse(finalFile(older).exists())
        assertTrue(finalFile(tapped).exists())
        assertTrue(finalFile(newer).exists())
        assertTrue(episodeDao.findById(oldest)!!.played)
        assertFalse(episodeDao.findById(tapped)!!.played)
        assertEquals(2, queue.getAll().count { it.type == SyncActionType.DELETE })
    }

    @Test
    fun `marking older episodes played keeps files when auto-delete is disabled`() = runTest {
        policy = DownloadPolicy(deleteWhenPlayed = false)
        val older = downloaded("a", published = 1)
        val tapped = downloaded("b", published = 2)

        assertEquals(1, manager.markOlderPlayed(tapped))

        assertTrue(finalFile(older).exists())
        assertTrue(episodeDao.findById(older)!!.played)
    }

    // --- eviction notices --------------------------------------------------------------------

    @Test
    fun `evicting unplayed downloads is recorded for the user to see`() = runTest {
        policy = DownloadPolicy(maxStorageBytes = 1000)
        downloaded("played", published = 1, played = true)
        downloaded("unplayed-old", published = 2)
        downloaded("unplayed-new", published = 3)

        manager.enforceRetention()

        // The played one goes first, then one unplayed to fit the cap.
        assertEquals(1, notices.unplayedRemoved.value)
    }

    @Test
    fun `evicting only played downloads is not worth a notice`() = runTest {
        policy = DownloadPolicy(maxStorageBytes = 1000)
        downloaded("a", published = 1, played = true)
        downloaded("b", published = 2)

        manager.enforceRetention()

        assertEquals(0, notices.unplayedRemoved.value)
    }

    @Test
    fun `notices accumulate across passes until dismissed`() = runTest {
        policy = DownloadPolicy(keepPerPodcast = 1)
        downloaded("a", published = 1)
        downloaded("b", published = 2)
        manager.enforceRetention()
        downloaded("c", published = 3)
        manager.enforceRetention()

        assertEquals(2, notices.unplayedRemoved.value)
        notices.dismiss()
        assertEquals(0, notices.unplayedRemoved.value)
    }
}
