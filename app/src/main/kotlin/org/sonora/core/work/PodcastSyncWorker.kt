// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Clock
import org.sonora.core.sync.PodcastSyncPipeline
import org.sonora.core.sync.WorkOutcome
import org.sonora.feature.settings.SettingsStore

@HiltWorker
class PodcastSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val pipeline: PodcastSyncPipeline,
    private val settings: SettingsStore,
    private val clock: Clock,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result =
        when (pipeline.run().toWorkOutcome(attempt = runAttemptCount)) {
            WorkOutcome.SUCCESS -> {
                settings.recordSync(clock.millis())
                Result.success()
            }
            WorkOutcome.RETRY -> Result.retry()
            WorkOutcome.FAILURE -> Result.failure()
        }
}
