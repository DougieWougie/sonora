// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.time.Clock
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import org.sonora.core.database.EpisodeDao
import org.sonora.core.database.EpisodeRepository
import org.sonora.core.database.PodcastDao
import org.sonora.core.database.PodcastRepository
import org.sonora.core.database.RoomTransactionRunner
import org.sonora.core.database.SonoraDatabase
import org.sonora.core.database.SubscriptionChangeDao
import org.sonora.core.database.SubscriptionRepository
import org.sonora.core.database.SyncQueueDao
import org.sonora.core.database.SyncStateDao
import org.sonora.core.database.TransactionRunner
import kotlinx.coroutines.flow.first
import org.sonora.core.download.EpisodeDownloadManager
import org.sonora.core.network.OkHttpFeedFetcher
import org.sonora.core.network.discovery.ApplePodcastSearchProvider
import org.sonora.core.network.discovery.DirectoryApiFactory
import org.sonora.core.network.discovery.PodcastIndexSearchProvider
import org.sonora.core.parser.RssFeedParser
import org.sonora.core.security.KeystoreSecretStore
import org.sonora.core.security.SecretStore
import org.sonora.core.sync.DownloadCanceller
import org.sonora.core.sync.DownloadScheduler
import org.sonora.core.sync.FeedFetcher
import org.sonora.core.sync.NextcloudRemoteSync
import org.sonora.core.sync.PodcastSyncPipeline
import org.sonora.core.sync.RemoteSync
import org.sonora.core.work.WorkManagerDownloadScheduler
import org.sonora.core.work.WorkManagerSyncScheduler
import org.sonora.feature.discover.DiscoveryRepository
import org.sonora.feature.settings.AppSettings
import org.sonora.core.download.DefaultEpisodeActions
import org.sonora.core.download.EpisodeActions
import org.sonora.core.media.PersistentPlayQueue
import org.sonora.core.media.PlayQueue
import org.sonora.core.media.PlaybackHub
import org.sonora.core.media.PlaybackPort
import org.sonora.core.media.PrefsQueueStorage
import org.sonora.core.network.LoginFlowApi
import org.sonora.core.network.NextcloudLoginFlow
import org.sonora.core.parser.OpmlParser
import org.sonora.core.parser.OpmlWriter
import org.sonora.core.work.SyncController
import org.sonora.feature.discover.DefaultOpmlTransfer
import org.sonora.feature.discover.DirectorySearch
import org.sonora.feature.discover.OpmlTransfer
import org.sonora.feature.settings.SettingsStore
import org.sonora.ui.theme.ThemePreferenceStore
import org.xmlpull.v1.XmlPullParserFactory

private const val USER_AGENT = "Sonora/0.1 (+https://sonora.invalid)"

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): SonoraDatabase =
        Room.databaseBuilder(context, SonoraDatabase::class.java, "sonora.db").build()

    @Provides fun podcastDao(db: SonoraDatabase): PodcastDao = db.podcastDao()
    @Provides fun episodeDao(db: SonoraDatabase): EpisodeDao = db.episodeDao()
    @Provides fun syncQueueDao(db: SonoraDatabase): SyncQueueDao = db.syncQueueDao()
    @Provides fun syncStateDao(db: SonoraDatabase): SyncStateDao = db.syncStateDao()
    @Provides fun subscriptionChangeDao(db: SonoraDatabase): SubscriptionChangeDao = db.subscriptionChangeDao()

    @Provides @Singleton
    fun transactions(db: SonoraDatabase): TransactionRunner = RoomTransactionRunner(db)

    @Provides @Singleton fun clock(): Clock = Clock.systemUTC()

    @Provides @Singleton
    fun okHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            chain.proceed(
                if (request.header("User-Agent") == null) request.newBuilder().header("User-Agent", USER_AGENT).build() else request,
            )
        }
        .build()

    @Provides @Singleton
    fun secretStore(@ApplicationContext context: Context): SecretStore = KeystoreSecretStore(context)

    @Provides @Singleton
    fun settings(@ApplicationContext context: Context, secrets: SecretStore) = AppSettings(context, secrets)

    @Provides @Singleton
    fun podcastRepository(podcasts: PodcastDao, episodes: EpisodeDao, tx: TransactionRunner, clock: Clock) =
        PodcastRepository(podcasts, episodes, tx, clock)

    @Provides @Singleton
    fun episodeRepository(
        podcasts: PodcastDao,
        episodes: EpisodeDao,
        queue: SyncQueueDao,
        tx: TransactionRunner,
        clock: Clock,
    ) = EpisodeRepository(podcasts, episodes, queue, tx, clock)

    @Provides @Singleton
    fun subscriptionRepository(podcasts: PodcastDao, changes: SubscriptionChangeDao, tx: TransactionRunner, clock: Clock) =
        SubscriptionRepository(podcasts, changes, tx, clock)

    @Provides @Singleton
    fun downloadManager(
        @ApplicationContext context: Context,
        episodes: EpisodeDao,
        repository: EpisodeRepository,
        client: OkHttpClient,
        settings: AppSettings,
    ) = EpisodeDownloadManager(
        episodeDao = episodes,
        episodeRepository = repository,
        client = client,
        downloadsDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "episodes"),
        policy = { settings.downloadPolicy() },
        ioDispatcher = Dispatchers.IO,
        allowCleartext = settings.allowCleartext,
    )

    @Provides @Singleton
    fun feedFetcher(client: OkHttpClient, settings: AppSettings): FeedFetcher = OkHttpFeedFetcher(
        client = client,
        parser = RssFeedParser(XmlPullParserFactory.newInstance()),
        ioDispatcher = Dispatchers.IO,
        userAgent = USER_AGENT,
        allowCleartext = settings.allowCleartext,
    )

    @Provides @Singleton
    fun remoteSync(
        settings: AppSettings,
        client: OkHttpClient,
        podcasts: PodcastDao,
        episodes: EpisodeDao,
        queue: SyncQueueDao,
        changes: SubscriptionChangeDao,
        state: SyncStateDao,
        tx: TransactionRunner,
        clock: Clock,
    ): RemoteSync = NextcloudRemoteSync(settings, client, podcasts, episodes, queue, changes, state, tx, clock)

    @Provides @Singleton
    fun workManagerDownloads(@ApplicationContext context: Context, manager: EpisodeDownloadManager) =
        WorkManagerDownloadScheduler(context, manager)

    @Provides @Singleton
    fun downloadScheduler(downloads: WorkManagerDownloadScheduler): DownloadScheduler = downloads

    @Provides @Singleton
    fun downloadCanceller(downloads: WorkManagerDownloadScheduler): DownloadCanceller = downloads

    @Provides @Singleton
    fun syncScheduler(@ApplicationContext context: Context) = WorkManagerSyncScheduler(context)

    @Provides @Singleton
    fun syncPipeline(
        remote: RemoteSync,
        podcasts: PodcastDao,
        episodes: EpisodeDao,
        repository: PodcastRepository,
        fetcher: FeedFetcher,
        downloads: DownloadScheduler,
        downloadManager: EpisodeDownloadManager,
        playback: PlaybackPort,
        settings: AppSettings,
    ) = PodcastSyncPipeline(
        remoteSync = remote,
        podcastDao = podcasts,
        episodeDao = episodes,
        podcastRepository = repository,
        feedFetcher = fetcher,
        downloads = downloads,
        unmeteredOnlyForAutoDownload = { settings.autoDownloadUnmeteredOnly },
        chargingOnlyForAutoDownload = { settings.autoDownloadChargingOnly },
        maxAutoDownloadsPerPodcast = { settings.autoDownloadPerPodcast },
        enforceRetention = {
            val playing = playback.state.first().episodeId
            downloadManager.enforceRetention(inUse = setOfNotNull(playing))
        },
    )

    @Provides @Singleton
    fun discovery(client: OkHttpClient, settings: AppSettings, clock: Clock): DiscoveryRepository {
        val cleartext = settings.allowCleartext
        return DiscoveryRepository(
            apple = ApplePodcastSearchProvider(DirectoryApiFactory.apple(client = client, allowCleartext = cleartext)),
            credentials = settings,
            podcastIndexFor = { credentials ->
                PodcastIndexSearchProvider(
                    DirectoryApiFactory.podcastIndex(
                        credentials = credentials,
                        userAgent = USER_AGENT,
                        clock = clock,
                        client = client,
                        allowCleartext = cleartext,
                    ),
                )
            },
        )
    }

    @Provides @Singleton
    fun themeStore(settings: AppSettings): ThemePreferenceStore = settings

    @Provides @Singleton
    fun settingsStore(settings: AppSettings): SettingsStore = settings

    @Provides @Singleton
    fun syncController(scheduler: WorkManagerSyncScheduler): SyncController = scheduler

    @Provides @Singleton
    fun playbackHub() = PlaybackHub()

    @Provides @Singleton
    fun playbackPort(hub: PlaybackHub): PlaybackPort = hub

    @Provides @Singleton
    fun playQueue(@ApplicationContext context: Context): PlayQueue = PersistentPlayQueue(PrefsQueueStorage(context))

    @Provides @Singleton
    fun episodeActions(
        manager: EpisodeDownloadManager,
        repository: EpisodeRepository,
        scheduler: DownloadScheduler,
        canceller: DownloadCanceller,
    ): EpisodeActions = DefaultEpisodeActions(manager, repository, scheduler, canceller)

    @Provides @Singleton
    fun directorySearch(repository: DiscoveryRepository): DirectorySearch = repository

    @Provides @Singleton
    fun loginFlow(client: OkHttpClient, settings: AppSettings): LoginFlowApi =
        NextcloudLoginFlow(client = client, userAgent = USER_AGENT, allowCleartext = settings.allowCleartext)

    @Provides @Singleton
    fun opmlTransfer(
        podcasts: PodcastDao,
        subscriptions: SubscriptionRepository,
        settings: AppSettings,
    ): OpmlTransfer {
        val factory = XmlPullParserFactory.newInstance()
        return DefaultOpmlTransfer(OpmlParser(factory), OpmlWriter(factory), podcasts, subscriptions) { settings.allowCleartext }
    }
}
