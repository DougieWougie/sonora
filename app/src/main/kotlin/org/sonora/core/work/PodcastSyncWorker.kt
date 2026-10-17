// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.sonora.core.sync.PodcastSyncPipeline
import org.sonora.core.sync.WorkOutcome

@HiltWorker
class PodcastSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val pipeline: PodcastSyncPipeline,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result =
        when (pipeline.run().toWorkOutcome(attempt = runAttemptCount)) {
            WorkOutcome.SUCCESS -> Result.success()
            WorkOutcome.RETRY -> Result.retry()
            WorkOutcome.FAILURE -> Result.failure()
        }
}
