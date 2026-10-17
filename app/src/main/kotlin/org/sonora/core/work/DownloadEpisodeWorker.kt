// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.work

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import org.sonora.core.database.EpisodeDao
import org.sonora.core.download.DownloadException
import org.sonora.core.download.EpisodeDownloadManager

/** Downloads one episode as a long-running foreground (dataSync) job. */
@HiltWorker
class DownloadEpisodeWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val manager: EpisodeDownloadManager,
    private val episodeDao: EpisodeDao,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val episodeId = inputData.getLong(KEY_EPISODE_ID, -1)
        val episode = episodeDao.findById(episodeId) ?: return Result.failure()
        showForeground(episode.title, 0, episode.sizeBytes)
        return try {
            manager.download(episodeId).collect { progress ->
                setProgress(Data.Builder().putLong(KEY_BYTES, progress.bytesDownloaded).build())
                showForeground(episode.title, progress.bytesDownloaded, progress.totalBytes)
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: DownloadException) {
            if (e.retryable && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    private suspend fun showForeground(title: String, bytes: Long, total: Long?) {
        val notification = DownloadNotifications.build(applicationContext, title, bytes, total)
        try {
            setForeground(
                ForegroundInfo(
                    DownloadNotifications.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                ),
            )
        } catch (_: IllegalStateException) {
            // Android 12+ may refuse a foreground start from the background; the download still proceeds.
        }
    }

    companion object {
        const val KEY_EPISODE_ID = "episodeId"
        const val KEY_BYTES = "bytes"
        const val MAX_ATTEMPTS = 5
    }
}
