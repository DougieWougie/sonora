// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.sonora.core.sync.FailureKind
import org.sonora.core.sync.PodcastSyncPipeline
import org.sonora.core.sync.RemoteStatus
import org.sonora.core.sync.SyncOutcome
import org.sonora.feature.settings.SettingsStore
import org.junit.Assert.assertEquals

@RunWith(AndroidJUnit4::class)
class PodcastSyncWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val pipeline = mockk<PodcastSyncPipeline>()
    private val settings = mockk<SettingsStore>(relaxed = true)
    private val clock = Clock.fixed(Instant.ofEpochMilli(42_000), ZoneOffset.UTC)

    private fun run(attempt: Int = 0): Result = runBlocking {
        TestListenableWorkerBuilder<PodcastSyncWorker>(context)
            .setRunAttemptCount(attempt)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                    PodcastSyncWorker(appContext, workerParameters, pipeline, settings, clock)
            })
            .build()
            .doWork()
    }

    private fun outcome(remote: RemoteStatus = RemoteStatus.NotConfigured, refreshed: Int = 1, failed: Map<String, FailureKind> = emptyMap()) =
        SyncOutcome(remote, refreshedFeeds = refreshed, failedFeeds = failed, queuedDownloads = 0)

    @Test
    fun `a clean sync succeeds and records when it happened`() {
        coEvery { pipeline.run() } returns outcome()

        assertEquals(Result.success(), run())

        verify { settings.recordSync(42_000) }
    }

    @Test
    fun `losing the server to a transient failure retries without recording a sync`() {
        coEvery { pipeline.run() } returns outcome(remote = RemoteStatus.Failed(FailureKind.TRANSIENT))

        assertEquals(Result.retry(), run())

        verify(exactly = 0) { settings.recordSync(any()) }
    }

    @Test
    fun `a transient failure on the last attempt gives up`() {
        coEvery { pipeline.run() } returns outcome(remote = RemoteStatus.Failed(FailureKind.TRANSIENT))

        assertEquals(Result.failure(), run(attempt = SyncOutcome.DEFAULT_MAX_ATTEMPTS))
    }

    @Test
    fun `rejected credentials fail rather than retry`() {
        coEvery { pipeline.run() } returns outcome(remote = RemoteStatus.Failed(FailureKind.AUTH))

        assertEquals(Result.failure(), run())
    }
}
