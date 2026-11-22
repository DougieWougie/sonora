// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import androidx.glance.appwidget.updateAll
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.sonora.core.database.EpisodeDao
import org.sonora.core.database.PodcastDao
import org.sonora.core.download.DownloadsMigration
import org.sonora.core.media.PlaybackHub
import org.sonora.core.network.withTransportPolicy
import org.sonora.core.work.WorkManagerSyncScheduler
import org.sonora.core.settings.AppSettings
import org.sonora.feature.discover.DirectorySuggestions
import org.sonora.core.media.PlayQueue
import org.sonora.feature.widget.LastPlayedStore
import org.sonora.feature.widget.MiniPlayerWidget
import org.sonora.feature.widget.miniPlayerWidgetStates
import org.sonora.feature.widget.redrawOnChange
import org.sonora.ui.HttpsUpgradeInterceptor

@HiltAndroidApp
class SonoraApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var syncScheduler: WorkManagerSyncScheduler
    @Inject lateinit var settings: AppSettings
    @Inject lateinit var okHttpClient: OkHttpClient
    @Inject lateinit var suggestions: DirectorySuggestions
    @Inject lateinit var downloadsMigration: DownloadsMigration
    @Inject lateinit var playbackHub: PlaybackHub
    @Inject lateinit var episodeDao: EpisodeDao
    @Inject lateinit var podcastDao: PodcastDao
    @Inject lateinit var playQueue: PlayQueue
    @Inject lateinit var lastPlayed: LastPlayedStore

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    /** Artwork goes through the app's own client, so it follows the same HTTPS-only policy as everything else. */
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val allowCleartext = settings.allowCleartext
        val client = okHttpClient
        return ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { client }))
                add(HttpsUpgradeInterceptor(allowCleartext = allowCleartext))
            }
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            syncScheduler.setPeriodicInterval(settings.syncIntervalHours)
            runCatching { downloadsMigration.run() }
        }
        // Redraw the home-screen mini player as playback changes, skipping work when no widget is placed.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            miniPlayerWidgetStates(playbackHub.state, playQueue.items, episodeDao, podcastDao, lastPlayed)
                .redrawOnChange(onFailure = { Log.w("MiniPlayerWidget", "Redraw failed", it) }) {
                    val ids = androidx.glance.appwidget.GlanceAppWidgetManager(this@SonoraApp)
                        .getGlanceIds(MiniPlayerWidget::class.java)
                    if (ids.isNotEmpty()) MiniPlayerWidget().updateAll(this@SonoraApp)
                }
        }
        // Warm Discover's suggestions so the tab has them by the time it is opened; failures are fine.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { runCatching { suggestions.suggestions() } }
    }
}
