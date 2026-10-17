// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.database

import java.time.Clock

/** Local subscribe/unsubscribe, recording each intent so the sync engine can push it. */
class SubscriptionRepository(
    private val podcastDao: PodcastDao,
    private val changeDao: SubscriptionChangeDao,
    private val transactions: TransactionRunner,
    private val clock: Clock,
) {
    /** Creates a placeholder row if needed; the next feed refresh fills in the real metadata. */
    suspend fun subscribe(feedUrl: String, title: String = feedUrl) = transactions.run {
        if (podcastDao.findByFeedUrl(feedUrl) == null) {
            podcastDao.insert(PodcastEntity(feedUrl = feedUrl, title = title))
        }
        changeDao.upsert(SubscriptionChangeEntity(feedUrl, subscribed = true, timestampUtcMillis = clock.millis()))
    }

    /** Episodes are removed with the podcast through the foreign key cascade. */
    suspend fun unsubscribe(feedUrl: String) = transactions.run {
        podcastDao.deleteByFeedUrl(feedUrl)
        changeDao.upsert(SubscriptionChangeEntity(feedUrl, subscribed = false, timestampUtcMillis = clock.millis()))
    }
}
