// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import org.sonora.core.download.DeviceConditions

/** Watches the network and charger state that background downloads are held back by. */
class DeviceConditionsMonitor(context: Context) {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val battery = appContext.getSystemService(BatteryManager::class.java)

    fun observe(): Flow<DeviceConditions> = callbackFlow {
        fun publish() {
            val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            val connected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val unmetered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
            trySend(DeviceConditions(connected, unmetered, battery.isCharging))
        }

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = publish()
            override fun onLost(network: Network) = publish()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = publish()
        }
        val powerReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = publish()
        }
        connectivity.registerDefaultNetworkCallback(networkCallback)
        appContext.registerReceiver(
            powerReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            },
        )
        publish()
        awaitClose {
            connectivity.unregisterNetworkCallback(networkCallback)
            appContext.unregisterReceiver(powerReceiver)
        }
    }.distinctUntilChanged()
}
