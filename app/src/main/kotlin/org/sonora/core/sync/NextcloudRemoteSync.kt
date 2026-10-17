// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.sync

import java.time.Clock
import okhttp3.OkHttpClient
import org.sonora.core.database.EpisodeDao
import org.sonora.core.database.PodcastDao
import org.sonora.core.database.SubscriptionChangeDao
import org.sonora.core.database.SyncQueueDao
import org.sonora.core.database.SyncStateDao
import org.sonora.core.database.TransactionRunner
import org.sonora.core.network.NextcloudApiFactory
import org.sonora.feature.settings.AppSettings

/** Builds a [SyncCoordinator] for the account currently stored in settings, if any. */
class NextcloudRemoteSync(
    private val settings: AppSettings,
    private val client: OkHttpClient,
    private val podcastDao: PodcastDao,
    private val episodeDao: EpisodeDao,
    private val syncQueueDao: SyncQueueDao,
    private val subscriptionChangeDao: SubscriptionChangeDao,
    private val syncStateDao: SyncStateDao,
    private val transactions: TransactionRunner,
    private val clock: Clock,
) : RemoteSync {
    override suspend fun sync(): SyncReport? {
        val account = settings.nextcloudAccount() ?: return null
        val api = NextcloudApiFactory.create(
            serverUrl = account.serverUrl,
            loginName = account.loginName,
            appPassword = account.appPassword,
            client = client,
            allowCleartext = settings.allowCleartext,
        )
        return SyncCoordinator(
            provider = NextcloudGpodderSyncProvider(api),
            podcastDao = podcastDao,
            episodeDao = episodeDao,
            syncQueueDao = syncQueueDao,
            subscriptionChangeDao = subscriptionChangeDao,
            syncStateDao = syncStateDao,
            transactions = transactions,
            clock = clock,
        ).sync()
    }
}
