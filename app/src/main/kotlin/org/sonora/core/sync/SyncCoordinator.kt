// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.sync

import java.time.Clock
import org.sonora.core.database.EpisodeDao
import org.sonora.core.database.EpisodeEntity
import org.sonora.core.database.PodcastDao
import org.sonora.core.database.PodcastEntity
import org.sonora.core.database.SubscriptionChangeDao
import org.sonora.core.database.SyncQueueActionEntity
import org.sonora.core.database.SyncQueueDao
import org.sonora.core.database.SyncStateDao
import org.sonora.core.database.SyncStateEntity
import org.sonora.core.database.TransactionRunner
import org.sonora.core.model.SyncActionType

data class SyncReport(
    val subscriptionsAdded: Int = 0,
    val subscriptionsRemoved: Int = 0,
    val episodeActionsApplied: Int = 0,
    val episodeActionsPushed: Int = 0,
)

/**
 * Reconciles local state with a [SyncProvider]: pull, resolve conflicts, then push.
 *
 * Network calls happen outside database transactions; each phase commits its own cursor only after
 * its local changes are stored, so a failure never loses unpushed local work (the queues are only
 * cleared after a successful push). Conflicts resolve to the most recent timestamp; ties go to the
 * local device.
 */
class SyncCoordinator(
    private val provider: SyncProvider,
    private val podcastDao: PodcastDao,
    private val episodeDao: EpisodeDao,
    private val syncQueueDao: SyncQueueDao,
    private val subscriptionChangeDao: SubscriptionChangeDao,
    private val syncStateDao: SyncStateDao,
    private val transactions: TransactionRunner,
    private val clock: Clock,
) {
    suspend fun sync(): SyncReport {
        val (added, removed) = syncSubscriptions()
        val (applied, pushed) = syncEpisodeActions()
        return SyncReport(added, removed, applied, pushed)
    }

    private suspend fun syncSubscriptions(): Pair<Int, Int> {
        val remote = provider.pullSubscriptions(currentState().subscriptionsSince)

        val pending = subscriptionChangeDao.getAll()
        val pendingUrls = pending.mapTo(HashSet()) { it.feedUrl }
        var added = 0
        var removed = 0
        transactions.run {
            for (url in remote.add) {
                if (url in pendingUrls || podcastDao.findByFeedUrl(url) != null) continue
                podcastDao.insert(PodcastEntity(feedUrl = url, title = url))
                added++
            }
            for (url in remote.remove) {
                if (url in pendingUrls || podcastDao.findByFeedUrl(url) == null) continue
                podcastDao.deleteByFeedUrl(url)
                removed++
            }
            syncStateDao.upsert(currentState().copy(subscriptionsSince = remote.timestamp))
        }

        if (pending.isNotEmpty()) {
            provider.pushSubscriptions(
                add = pending.filter { it.subscribed }.map { it.feedUrl },
                remove = pending.filterNot { it.subscribed }.map { it.feedUrl },
            )
            transactions.run {
                // Keep rows the user changed again while the push was in flight.
                val unchanged = subscriptionChangeDao.getAll().filter { it in pending }
                subscriptionChangeDao.deleteByFeedUrls(unchanged.map { it.feedUrl })
            }
        }
        return added to removed
    }

    private suspend fun syncEpisodeActions(): Pair<Int, Int> {
        val page = provider.pullEpisodeActions(currentState().episodeActionsSince)

        var applied = 0
        transactions.run {
            val latestRemote = page.actions
                .filter { it.type == SyncActionType.PLAY || it.type == SyncActionType.NEW }
                .groupBy { it.episodeUrl }
                .mapValues { (_, actions) -> actions.maxBy { it.timestampUtcMillis } }
            val pending = syncQueueDao.getAll()

            for ((episodeUrl, action) in latestRemote) {
                val episode = episodeDao.findByEnclosureUrl(episodeUrl) ?: continue
                val localTimestamp = pending
                    .filter { it.episodeUrl == episodeUrl && (it.type == SyncActionType.PLAY || it.type == SyncActionType.NEW) }
                    .maxOfOrNull { it.timestampUtcMillis }
                if (localTimestamp != null && action.timestampUtcMillis <= localTimestamp) continue

                episodeDao.update(action.applyTo(episode))
                syncQueueDao.deleteByTypeAndEpisode(SyncActionType.PLAY, episodeUrl)
                syncQueueDao.deleteByTypeAndEpisode(SyncActionType.NEW, episodeUrl)
                applied++
            }
            syncStateDao.upsert(currentState().copy(episodeActionsSince = page.timestamp))
        }

        val outgoing = syncQueueDao.getAll()
        if (outgoing.isNotEmpty()) {
            provider.pushEpisodeActions(outgoing.map { it.toRemote() })
            syncQueueDao.deleteByIds(outgoing.map { it.id })
        }
        return applied to outgoing.size
    }

    private suspend fun currentState(): SyncStateEntity = syncStateDao.get() ?: SyncStateEntity()

    private fun RemoteEpisodeAction.applyTo(episode: EpisodeEntity): EpisodeEntity = when (type) {
        SyncActionType.NEW -> episode.copy(playbackPositionSeconds = 0, played = false)
        else -> {
            val position = positionSeconds ?: episode.playbackPositionSeconds
            val total = totalSeconds ?: episode.durationSeconds
            episode.copy(
                playbackPositionSeconds = position,
                played = if (total != null && total > 0) position >= total else episode.played,
            )
        }
    }

    private fun SyncQueueActionEntity.toRemote() = RemoteEpisodeAction(
        feedUrl = feedUrl,
        episodeUrl = episodeUrl,
        guid = guid,
        type = type,
        timestampUtcMillis = timestampUtcMillis,
        startedSeconds = startedSeconds,
        positionSeconds = positionSeconds,
        totalSeconds = totalSeconds,
    )
}
