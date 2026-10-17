// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import okhttp3.OkHttpClient
import org.sonora.core.network.withTransportPolicy
import org.sonora.core.work.WorkManagerSyncScheduler
import org.sonora.feature.settings.AppSettings
import org.sonora.ui.HttpsUpgradeInterceptor

@HiltAndroidApp
class SonoraApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var syncScheduler: WorkManagerSyncScheduler
    @Inject lateinit var settings: AppSettings
    @Inject lateinit var okHttpClient: OkHttpClient

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    /** Artwork goes through the app's own client, so it follows the same HTTPS-only policy as everything else. */
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val allowCleartext = settings.allowCleartext
        val client = okHttpClient.withTransportPolicy(allowCleartext)
        return ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { client }))
                if (!allowCleartext) add(HttpsUpgradeInterceptor())
            }
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        syncScheduler.schedulePeriodic(settings.syncIntervalHours)
    }
}
