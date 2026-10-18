// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.download

import org.sonora.core.database.EpisodeRepository
import org.sonora.core.sync.DownloadScheduler

class DefaultEpisodeActions(
    private val manager: EpisodeDownloadManager,
    private val repository: EpisodeRepository,
    private val scheduler: DownloadScheduler,
) : EpisodeActions {
    /** The user asked for this one, so it must not wait for an unmetered network. */
    override suspend fun download(episodeId: Long) = scheduler.schedule(listOf(episodeId), unmeteredOnly = false)
    override suspend fun deleteDownload(episodeId: Long) = manager.deleteDownload(episodeId)
    override suspend fun markPlayed(episodeId: Long) = manager.markPlayed(episodeId)
    override suspend fun markUnplayed(episodeId: Long) = repository.markNew(episodeId)
    override suspend fun countOlderUnplayed(episodeId: Long) = repository.countOlderUnplayed(episodeId)
    override suspend fun markOlderPlayed(episodeId: Long) = manager.markOlderPlayed(episodeId)
}
