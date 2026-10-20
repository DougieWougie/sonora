// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.sonora.core.download.DeviceConditions
import org.sonora.core.download.EpisodeDownloadManager
import org.sonora.core.download.WaitReason
import org.junit.Assert.assertEquals

@RunWith(AndroidJUnit4::class)
class WorkManagerDownloadWaitsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = mockk<EpisodeDownloadManager>()
    private val conditions = MutableStateFlow(DeviceConditions(connected = true, unmetered = true, charging = true))
    private val device = mockk<DeviceConditionsMonitor> { every { observe() } returns conditions }

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        coEvery { manager.enqueue(any()) } returns true
    }

    private fun waits() = WorkManagerDownloadWaits(context, device).observe()

    private suspend fun schedule(vararg ids: Long, unmeteredOnly: Boolean = false, chargingOnly: Boolean = false) =
        WorkManagerDownloadScheduler(context, manager).schedule(ids.toList(), unmeteredOnly, chargingOnly)

    @Test
    fun `nothing is waiting when nothing is queued`() = runBlocking {
        waits().test {
            assertEquals(emptyMap<Long, WaitReason>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a queued download whose needs are met is not waiting`() = runBlocking {
        schedule(1, unmeteredOnly = true, chargingOnly = true)

        waits().test {
            assertEquals(emptyMap<Long, WaitReason>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a wifi-only download waits for wifi on a metered connection`() = runBlocking {
        conditions.value = DeviceConditions(connected = true, unmetered = false, charging = true)
        schedule(1, unmeteredOnly = true)

        waits().test {
            assertEquals(mapOf(1L to WaitReason.WIFI), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a charging-only download waits for the charger`() = runBlocking {
        conditions.value = DeviceConditions(connected = true, unmetered = true, charging = false)
        schedule(2, chargingOnly = true)

        waits().test {
            assertEquals(mapOf(2L to WaitReason.CHARGER), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an offline device holds every queued download for a connection`() = runBlocking {
        conditions.value = DeviceConditions(connected = false, unmetered = false, charging = false)
        schedule(1, 2)

        waits().test {
            assertEquals(mapOf(1L to WaitReason.CONNECTION, 2L to WaitReason.CONNECTION), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the reason clears when the device changes`() = runBlocking {
        conditions.value = DeviceConditions(connected = false, unmetered = false, charging = false)
        schedule(1)

        waits().test {
            assertEquals(mapOf(1L to WaitReason.CONNECTION), awaitItem())
            conditions.value = DeviceConditions(connected = true, unmetered = false, charging = false)
            assertEquals(emptyMap<Long, WaitReason>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
