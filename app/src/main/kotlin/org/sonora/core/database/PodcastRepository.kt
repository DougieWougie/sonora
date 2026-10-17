// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.database

import java.time.Clock
import kotlinx.coroutines.flow.Flow
import org.sonora.core.model.ParsedEpisode
import org.sonora.core.model.ParsedFeed

class PodcastRepository(
    private val podcastDao: PodcastDao,
    private val episodeDao: EpisodeDao,
    private val transactions: TransactionRunner,
    private val clock: Clock,
) {
    fun observePodcasts(): Flow<List<PodcastEntity>> = podcastDao.observeAll()

    /** Merges a freshly parsed feed into the database, keeping all local episode state. Returns the podcast id. */
    suspend fun refresh(feedUrl: String, feed: ParsedFeed): Long = transactions.run {
        val now = clock.millis()
        val existing = podcastDao.findByFeedUrl(feedUrl)
        val podcastId = if (existing == null) {
            podcastDao.insert(feed.toEntity(feedUrl, now))
        } else {
            podcastDao.update(feed.toEntity(feedUrl, now).copy(id = existing.id))
            existing.id
        }

        val local = episodeDao.getForPodcast(podcastId).associateBy { it.guid }
        val (known, fresh) = feed.episodes.distinctBy { it.guid }.partition { it.guid in local }
        episodeDao.updateAll(known.map { parsed -> parsed.mergeInto(local.getValue(parsed.guid)) })
        episodeDao.insertAll(fresh.map { it.toEntity(podcastId) })
        podcastId
    }

    private fun ParsedFeed.toEntity(feedUrl: String, refreshedAt: Long) = PodcastEntity(
        feedUrl = feedUrl,
        title = title,
        description = description,
        link = link,
        author = author,
        imageUrl = imageUrl,
        lastRefreshedAtMillis = refreshedAt,
    )

    private fun ParsedEpisode.toEntity(podcastId: Long) = EpisodeEntity(
        podcastId = podcastId,
        guid = guid,
        title = title,
        description = description,
        enclosureUrl = enclosureUrl,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        durationSeconds = durationSeconds,
        publishedAtMillis = publishedAtMillis,
        imageUrl = imageUrl,
    )

    /** Copies feed-owned fields onto the stored row; playback and download state stay untouched. */
    private fun ParsedEpisode.mergeInto(stored: EpisodeEntity) = stored.copy(
        title = title,
        description = description,
        enclosureUrl = enclosureUrl,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        durationSeconds = durationSeconds,
        publishedAtMillis = publishedAtMillis,
        imageUrl = imageUrl,
    )
}
