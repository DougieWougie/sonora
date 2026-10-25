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

    /** Cheap periodic checkpoint: stores the position only, without touching the sync queue. */
    suspend fun savePosition(episodeId: Long, positionSeconds: Long) {
        if (episodeDao.updatePosition(episodeId, positionSeconds) == 0) throw NoSuchElementException("No episode with id $episodeId")
    }

    suspend fun markPlayed(episodeId: Long) = transactions.run { markPlayed(requireEpisode(episodeId)) }

    private suspend fun markPlayed(episode: EpisodeEntity) {
        val end = episode.durationSeconds ?: episode.playbackPositionSeconds
        episodeDao.update(episode.copy(played = true, playbackPositionSeconds = end))
        enqueuePlay(episode, startedSeconds = 0, positionSeconds = end)
    }

    /** How many unplayed episodes of the same podcast were published before this one. */
    suspend fun countOlderUnplayed(episodeId: Long): Int = olderUnplayed(requireEpisode(episodeId)).size

    /** Marks those episodes played in one transaction and returns their ids. */
    suspend fun markOlderPlayed(episodeId: Long): List<Long> = transactions.run {
        olderUnplayed(requireEpisode(episodeId)).onEach { markPlayed(it) }.map { it.id }
    }

    /** Marks every unplayed episode of the podcast played in one transaction and returns their ids. */
    suspend fun markAllPlayed(podcastId: Long): List<Long> = transactions.run {
        episodeDao.getUnplayedForPodcast(podcastId).onEach { markPlayed(it) }.map { it.id }
    }

    /** Episodes without a publish date have no place in the order, so they are never "older". */
    private suspend fun olderUnplayed(episode: EpisodeEntity): List<EpisodeEntity> {
        val published = episode.publishedAtMillis ?: return emptyList()
        return episodeDao.getOlderUnplayed(episode.podcastId, published)
    }

    suspend fun markNew(episodeId: Long) = transactions.run {
        val episode = requireEpisode(episodeId)
        episodeDao.update(episode.copy(played = false, playbackPositionSeconds = 0))
        syncQueueDao.deleteByTypeAndEpisode(SyncActionType.PLAY, episode.enclosureUrl)
        enqueue(episode, SyncActionType.NEW)
    }

    /** Tracks transfer progress locally; only completion and deletion are worth telling the server. */
    suspend fun updateDownload(episodeId: Long, state: DownloadState, bytes: Long) {
        val episode = requireEpisode(episodeId)
        episodeDao.update(
            episode.copy(
                downloadState = state,
                downloadedBytes = bytes,
                downloadPath = episode.downloadPath.takeIf { state == DownloadState.DOWNLOADED },
            ),
        )
    }

    suspend fun markDownloaded(episodeId: Long, path: String, bytes: Long? = null) = transactions.run {
        val episode = requireEpisode(episodeId)
        episodeDao.update(
            episode.copy(
                downloadState = DownloadState.DOWNLOADED,
                downloadPath = path,
                downloadedBytes = bytes ?: episode.downloadedBytes,
            ),
        )
        enqueue(episode, SyncActionType.DOWNLOAD)
    }

    suspend fun deleteDownload(episodeId: Long) = transactions.run {
        val episode = requireEpisode(episodeId)
        episodeDao.update(episode.copy(downloadState = DownloadState.NONE, downloadPath = null, downloadedBytes = 0))
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
