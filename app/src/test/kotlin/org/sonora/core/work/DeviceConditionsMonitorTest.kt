// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.work

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.sonora.core.download.DeviceConditions
import org.junit.Assert.assertEquals

@RunWith(AndroidJUnit4::class)
class DeviceConditionsMonitorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val shadowConnectivity = shadowOf(connectivity)
    private val battery = context.getSystemService(BatteryManager::class.java)

    private fun network(vararg capabilities: Int) {
        val caps = ShadowNetworkCapabilities.newInstance()
        capabilities.forEach { shadowOf(caps).addCapability(it) }
        shadowConnectivity.setNetworkCapabilities(connectivity.activeNetwork, caps)
    }

    private val validated = intArrayOf(NetworkCapabilities.NET_CAPABILITY_INTERNET, NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    @Test
    fun `reports the current state straight away`() = runBlocking {
        network(*validated, NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        shadowOf(battery).setIsCharging(true)

        DeviceConditionsMonitor(context).observe().test {
            assertEquals(DeviceConditions(connected = true, unmetered = true, charging = true), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a metered connection is connected but not unmetered`() = runBlocking {
        network(*validated)
        shadowOf(battery).setIsCharging(false)

        DeviceConditionsMonitor(context).observe().test {
            assertEquals(DeviceConditions(connected = true, unmetered = false, charging = false), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a network that is not validated does not count as connected`() = runBlocking {
        network(NetworkCapabilities.NET_CAPABILITY_INTERNET)

        DeviceConditionsMonitor(context).observe().test {
            assertEquals(false, awaitItem().connected)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `plugging in and unplugging the charger is reported`() = runBlocking {
        network(*validated)
        shadowOf(battery).setIsCharging(false)

        DeviceConditionsMonitor(context).observe().test {
            assertEquals(false, awaitItem().charging)

            shadowOf(battery).setIsCharging(true)
            context.sendBroadcast(Intent(Intent.ACTION_POWER_CONNECTED))
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(true, awaitItem().charging)

            shadowOf(battery).setIsCharging(false)
            context.sendBroadcast(Intent(Intent.ACTION_POWER_DISCONNECTED))
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(false, awaitItem().charging)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `losing the network and getting it back is reported`() = runBlocking {
        network(*validated)

        DeviceConditionsMonitor(context).observe().test {
            assertEquals(true, awaitItem().connected)

            shadowConnectivity.setNetworkCapabilities(connectivity.activeNetwork, ShadowNetworkCapabilities.newInstance())
            shadowConnectivity.networkCallbacks.forEach { it.onLost(connectivity.activeNetwork!!) }
            assertEquals(false, awaitItem().connected)

            network(*validated)
            shadowConnectivity.networkCallbacks.forEach { it.onAvailable(connectivity.activeNetwork!!) }
            assertEquals(true, awaitItem().connected)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an unchanged state is not repeated`() = runBlocking {
        network(*validated)

        DeviceConditionsMonitor(context).observe().test {
            awaitItem()
            shadowConnectivity.networkCallbacks.forEach { it.onAvailable(connectivity.activeNetwork!!) }
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `stops listening when collection ends`() = runBlocking {
        network(*validated)

        DeviceConditionsMonitor(context).observe().test {
            awaitItem()
            assertEquals(1, shadowConnectivity.networkCallbacks.size)
            cancelAndIgnoreRemainingEvents()
        }
        kotlinx.coroutines.yield()

        assertEquals(0, shadowConnectivity.networkCallbacks.size)
    }
}
