// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.sonora.core.download.EpisodeDownloadManager

@RunWith(AndroidJUnit4::class)
class WorkManagerSchedulersTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        val config = Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
    }

    private fun infos(name: String) = workManager.getWorkInfosForUniqueWork(name).get()

    // --- sync ----------------------------------------------------------------------------------

    @Test
    fun `sync now enqueues one networked request and ignores a second while pending`() {
        val scheduler = WorkManagerSyncScheduler(context)

        scheduler.syncNow()
        scheduler.syncNow()

        val pending = infos("podcast-sync-now")
        assertEquals(1, pending.size)
        assertEquals(WorkInfo.State.ENQUEUED, pending.single().state)
        assertEquals(NetworkType.CONNECTED, pending.single().constraints.requiredNetworkType)
    }

    @Test
    fun `syncing is true while a sync is pending`() = runBlocking {
        val scheduler = WorkManagerSyncScheduler(context)

        scheduler.syncing.test {
            assertFalse(awaitItem())
            scheduler.syncNow()
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a positive interval schedules periodic sync needing network and battery`() {
        val scheduler = WorkManagerSyncScheduler(context)

        scheduler.setPeriodicInterval(6)

        val work = infos("podcast-sync").single()
        assertEquals(WorkInfo.State.ENQUEUED, work.state)
        assertEquals(6L, work.periodicityInfo?.repeatIntervalMillis?.div(HOUR_MILLIS))
        assertEquals(NetworkType.CONNECTED, work.constraints.requiredNetworkType)
        assertTrue(work.constraints.requiresBatteryNotLow())
    }

    @Test
    fun `scheduling periodic sync keeps the interval at one hour or more`() {
        WorkManagerSyncScheduler(context).schedulePeriodic(0)

        val work = infos("podcast-sync").single()
        assertEquals(1L, work.periodicityInfo?.repeatIntervalMillis?.div(HOUR_MILLIS))
    }

    @Test
    fun `a zero interval cancels periodic sync`() {
        val scheduler = WorkManagerSyncScheduler(context)
        scheduler.setPeriodicInterval(6)

        scheduler.setPeriodicInterval(0)

        assertEquals(WorkInfo.State.CANCELLED, infos("podcast-sync").single().state)
    }

    // --- downloads -----------------------------------------------------------------------------

    private val manager = mockk<EpisodeDownloadManager>()

    @Test
    fun `scheduling downloads enqueues only the episodes the manager accepts`() = runBlocking {
        coEvery { manager.enqueue(1) } returns true
        coEvery { manager.enqueue(2) } returns false

        WorkManagerDownloadScheduler(context, manager).schedule(listOf(1, 2), unmeteredOnly = false, chargingOnly = false)

        assertEquals(1, infos("download-1").size)
        assertTrue(infos("download-2").isEmpty())
    }

    @Test
    fun `download work carries the episode tags and the default constraints`() = runBlocking {
        coEvery { manager.enqueue(5) } returns true

        WorkManagerDownloadScheduler(context, manager).schedule(listOf(5), unmeteredOnly = false, chargingOnly = false)

        val work = infos("download-5").single()
        assertTrue(WorkManagerDownloadScheduler.TAG_DOWNLOAD in work.tags)
        val episodeIds = work.tags.mapNotNull(WorkManagerDownloadScheduler::episodeIdFromTag)
        assertEquals(listOf(5L), episodeIds)
        assertEquals(NetworkType.CONNECTED, work.constraints.requiredNetworkType)
        assertFalse(work.constraints.requiresCharging())
    }

    @Test
    fun `unmetered and charging are required when asked for`() = runBlocking {
        coEvery { manager.enqueue(5) } returns true

        WorkManagerDownloadScheduler(context, manager).schedule(listOf(5), unmeteredOnly = true, chargingOnly = true)

        val work = infos("download-5").single()
        assertEquals(NetworkType.UNMETERED, work.constraints.requiredNetworkType)
        assertTrue(work.constraints.requiresCharging())
    }

    @Test
    fun `cancelling a download cancels its work`() = runBlocking {
        coEvery { manager.enqueue(5) } returns true
        val scheduler = WorkManagerDownloadScheduler(context, manager)
        scheduler.schedule(listOf(5), unmeteredOnly = false, chargingOnly = false)

        scheduler.cancel(5)

        assertEquals(WorkInfo.State.CANCELLED, infos("download-5").single().state)
    }

    @Test
    fun `only episode tags decode to an id`() {
        assertEquals(42L, WorkManagerDownloadScheduler.episodeIdFromTag("download-episode-42"))
        assertNull(WorkManagerDownloadScheduler.episodeIdFromTag("download"))
        assertNull(WorkManagerDownloadScheduler.episodeIdFromTag("download-episode-x"))
    }

    private companion object {
        const val HOUR_MILLIS = 3_600_000L
    }
}
