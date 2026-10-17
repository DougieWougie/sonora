// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import org.sonora.core.work.WorkManagerSyncScheduler
import org.sonora.feature.settings.AppSettings

@HiltAndroidApp
class SonoraApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var syncScheduler: WorkManagerSyncScheduler
    @Inject lateinit var settings: AppSettings

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        syncScheduler.schedulePeriodic(settings.syncIntervalHours)
    }
}
