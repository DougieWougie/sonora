// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.download

import org.sonora.core.database.EpisodeRepository
import org.sonora.core.sync.DownloadCanceller
import org.sonora.core.sync.DownloadScheduler

class DefaultEpisodeActions(
    private val manager: EpisodeDownloadManager,
    private val repository: EpisodeRepository,
    private val scheduler: DownloadScheduler,
    private val canceller: DownloadCanceller,
) : EpisodeActions {
    /** The user asked for this one, so it must not wait for an unmetered network. */
    override suspend fun download(episodeId: Long) = scheduler.schedule(listOf(episodeId), unmeteredOnly = false, chargingOnly = false)
    override suspend fun deleteDownload(episodeId: Long) = manager.deleteDownload(episodeId)

    /** Cleans up first: the job's cancellation then finds the episode already reset instead of re-queueing it. */
    override suspend fun cancelDownload(episodeId: Long) {
        manager.cancelDownload(episodeId)
        canceller.cancel(episodeId)
    }

    override suspend fun deleteAllPlayedDownloads(): Int = manager.deleteAllPlayedDownloads().size

    override suspend fun markPlayed(episodeId: Long) = manager.markPlayed(episodeId)
    override suspend fun markUnplayed(episodeId: Long) = repository.markNew(episodeId)
}
