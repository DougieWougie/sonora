// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.database

import java.time.Clock
import kotlinx.coroutines.flow.Flow
import org.sonora.core.model.DownloadState
import org.sonora.core.model.SyncActionType

/** Tracks per-episode offline state and records each local change in the sync queue. */
class EpisodeRepository(
    private val podcastDao: PodcastDao,
    private val episodeDao: EpisodeDao,
    private val syncQueueDao: SyncQueueDao,
    private val transactions: TransactionRunner,
    private val clock: Clock,
) {
    fun observeEpisodes(podcastId: Long): Flow<List<EpisodeEntity>> = episodeDao.observeForPodcast(podcastId)

    suspend fun recordPlayback(episodeId: Long, startedSeconds: Long, positionSeconds: Long) = transactions.run {
        val episode = requireEpisode(episodeId)
        val total = episode.durationSeconds
        val finished = total != null && positionSeconds >= total
        episodeDao.update(
            episode.copy(
                playbackPositionSeconds = positionSeconds,
                played = episode.played || finished,
            ),
        )
        enqueuePlay(episode, startedSeconds, positionSeconds)
    }

    suspend fun markPlayed(episodeId: Long) = transactions.run {
        val episode = requireEpisode(episodeId)
        val end = episode.durationSeconds ?: episode.playbackPositionSeconds
        episodeDao.update(episode.copy(played = true, playbackPositionSeconds = end))
        enqueuePlay(episode, startedSeconds = 0, positionSeconds = end)
    }

    suspend fun markNew(episodeId: Long) = transactions.run {
        val episode = requireEpisode(episodeId)
        episodeDao.update(episode.copy(played = false, playbackPositionSeconds = 0))
        syncQueueDao.deleteByTypeAndEpisode(SyncActionType.PLAY, episode.enclosureUrl)
        enqueue(episode, SyncActionType.NEW)
    }

    suspend fun markDownloaded(episodeId: Long, path: String) = transactions.run {
        val episode = requireEpisode(episodeId)
        episodeDao.update(episode.copy(downloadState = DownloadState.DOWNLOADED, downloadPath = path))
        enqueue(episode, SyncActionType.DOWNLOAD)
    }

    suspend fun deleteDownload(episodeId: Long) = transactions.run {
        val episode = requireEpisode(episodeId)
        episodeDao.update(episode.copy(downloadState = DownloadState.NONE, downloadPath = null))
        enqueue(episode, SyncActionType.DELETE)
    }

    private suspend fun requireEpisode(id: Long): EpisodeEntity =
        episodeDao.findById(id) ?: throw NoSuchElementException("No episode with id $id")

    /** Only the latest position matters to the server, so older pending PLAY actions are replaced. */
    private suspend fun enqueuePlay(episode: EpisodeEntity, startedSeconds: Long, positionSeconds: Long) {
        syncQueueDao.deleteByTypeAndEpisode(SyncActionType.PLAY, episode.enclosureUrl)
        enqueue(episode, SyncActionType.PLAY, startedSeconds, positionSeconds, episode.durationSeconds)
    }

    private suspend fun enqueue(
        episode: EpisodeEntity,
        type: SyncActionType,
        started: Long? = null,
        position: Long? = null,
        total: Long? = null,
    ) {
        val feedUrl = podcastDao.findById(episode.podcastId)?.feedUrl
            ?: throw NoSuchElementException("No podcast with id ${episode.podcastId}")
        syncQueueDao.insert(
            SyncQueueActionEntity(
                type = type,
                feedUrl = feedUrl,
                episodeUrl = episode.enclosureUrl,
                guid = episode.guid,
                timestampUtcMillis = clock.millis(),
                startedSeconds = started,
                positionSeconds = position,
                totalSeconds = total,
            ),
        )
    }
}
