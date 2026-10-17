// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.sonora.core.download.EpisodeDownloadManager
import org.sonora.core.sync.DownloadScheduler

/** Schedules the periodic background sync (requires a network connection). */
class WorkManagerSyncScheduler(context: Context) : SyncController {
    private val workManager = WorkManager.getInstance(context)

    override val syncing: Flow<Boolean> = workManager.getWorkInfosForUniqueWorkFlow(SYNC_NOW_WORK_NAME)
        .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED } }

    /** Runs one sync as soon as the network allows; a request while one is pending is ignored. */
    override fun syncNow() {
        val request = OneTimeWorkRequestBuilder<PodcastSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniqueWork(SYNC_NOW_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    fun schedulePeriodic(intervalHours: Long) {
        val request = PeriodicWorkRequestBuilder<PodcastSyncWorker>(intervalHours.coerceAtLeast(1), TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(SYNC_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancelPeriodic() {
        workManager.cancelUniqueWork(SYNC_WORK_NAME)
    }

    private companion object {
        const val SYNC_WORK_NAME = "podcast-sync"
        const val SYNC_NOW_WORK_NAME = "podcast-sync-now"
        const val BACKOFF_MINUTES = 10L
    }
}

/** Marks episodes QUEUED and hands them to WorkManager; UNMETERED is required when the user asked for it. */
class WorkManagerDownloadScheduler(
    context: Context,
    private val manager: EpisodeDownloadManager,
) : DownloadScheduler {
    private val workManager = WorkManager.getInstance(context)

    override suspend fun schedule(episodeIds: List<Long>, unmeteredOnly: Boolean) {
        val network = if (unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        val constraints = Constraints.Builder().setRequiredNetworkType(network).build()
        for (id in episodeIds) {
            if (!manager.enqueue(id)) continue
            val request = OneTimeWorkRequestBuilder<DownloadEpisodeWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(DownloadEpisodeWorker.KEY_EPISODE_ID to id))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
                .build()
            workManager.enqueueUniqueWork("download-$id", ExistingWorkPolicy.KEEP, request)
        }
    }

    private companion object {
        const val BACKOFF_MINUTES = 5L
    }
}
