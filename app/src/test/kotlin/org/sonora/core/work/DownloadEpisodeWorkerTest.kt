// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.sonora.core.database.EpisodeDao
import org.sonora.core.download.DownloadException
import org.sonora.core.download.DownloadProgress
import org.sonora.core.download.EpisodeDownloadManager
import org.sonora.testutil.episodeEntity

@RunWith(AndroidJUnit4::class)
class DownloadEpisodeWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = mockk<EpisodeDownloadManager>()
    private val dao = mockk<EpisodeDao>()
    private val episode = episodeEntity(podcastId = 1, guid = "g", title = "The Episode", sizeBytes = 1_000).copy(id = 7)

    private fun worker(episodeId: Long = 7, attempt: Int = 0) =
        TestListenableWorkerBuilder<DownloadEpisodeWorker>(context)
            .setInputData(Data.Builder().putLong(DownloadEpisodeWorker.KEY_EPISODE_ID, episodeId).build())
            .setRunAttemptCount(attempt)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                    DownloadEpisodeWorker(appContext, workerParameters, manager, dao)
            })
            .build()

    private fun run(episodeId: Long = 7, attempt: Int = 0): Result = runBlocking { worker(episodeId, attempt).doWork() }

    private fun givenEpisode() = coEvery { dao.findById(7) } returns episode

    // --- the happy path ------------------------------------------------------------------------

    @Test
    fun `a finished download succeeds and then applies retention, protecting the new episode`() {
        givenEpisode()
        every { manager.download(7) } returns flowOf(DownloadProgress(100, 1_000), DownloadProgress(1_000, 1_000))
        coEvery { manager.enforceRetention(any()) } returns emptyList()

        assertEquals(Result.success(), run())

        coVerify { manager.enforceRetention(setOf(7L)) }
    }

    @Test
    fun `a retention failure does not undo a finished download`() {
        givenEpisode()
        every { manager.download(7) } returns flowOf(DownloadProgress(1_000, 1_000))
        coEvery { manager.enforceRetention(any()) } throws IllegalStateException("disk")

        assertEquals(Result.success(), run())
    }

    @Test
    fun `cancellation during retention is not swallowed`() {
        givenEpisode()
        every { manager.download(7) } returns flowOf(DownloadProgress(1_000, 1_000))
        coEvery { manager.enforceRetention(any()) } throws CancellationException("stopped")

        assertThrows(CancellationException::class.java) { run() }
    }

    // --- failures ------------------------------------------------------------------------------

    @Test
    fun `an unknown episode fails without downloading`() {
        coEvery { dao.findById(99) } returns null

        assertEquals(Result.failure(), run(episodeId = 99))

        io.mockk.verify(exactly = 0) { manager.download(any()) }
    }

    @Test
    fun `a retryable failure is retried while attempts remain`() {
        givenEpisode()
        every { manager.download(7) } returns flow { throw DownloadException("offline", retryable = true) }

        assertEquals(Result.retry(), run(attempt = DownloadEpisodeWorker.MAX_ATTEMPTS - 1))
    }

    @Test
    fun `a retryable failure gives up after the last attempt`() {
        givenEpisode()
        every { manager.download(7) } returns flow { throw DownloadException("offline", retryable = true) }

        assertEquals(Result.failure(), run(attempt = DownloadEpisodeWorker.MAX_ATTEMPTS))
    }

    @Test
    fun `a permanent failure is not retried`() {
        givenEpisode()
        every { manager.download(7) } returns flow { throw DownloadException("404", retryable = false) }

        assertEquals(Result.failure(), run())
    }

    @Test
    fun `cancelling the download is not turned into a result`() {
        givenEpisode()
        every { manager.download(7) } returns flow { throw CancellationException("user cancelled") }

        assertThrows(CancellationException::class.java) { run() }
    }
}
