// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.sonora.core.database.SonoraDatabase
import org.sonora.core.security.KeystoreSecretStore
import org.sonora.testutil.FakeAndroidKeyStore

@RunWith(AndroidJUnit4::class)
class AppModuleTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val module = AppModule
    private val server = MockWebServer()
    private lateinit var db: SonoraDatabase

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        FakeAndroidKeyStore.install()
        db = module.database(context)
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
        db.close()
        FakeAndroidKeyStore.uninstall()
    }

    private fun userAgentSentBy(client: OkHttpClient, configure: Request.Builder.() -> Unit = {}): String? {
        server.enqueue(MockResponse.Builder().build())
        client.newCall(Request.Builder().url(server.url("/")).apply(configure).build()).execute().close()
        return server.takeRequest().headers["User-Agent"]
    }

    @Test
    fun `requests identify the app unless they set their own user agent`() {
        val client = module.okHttpClient()

        assertEquals("Sonora/0.1 (+https://sonora.invalid)", userAgentSentBy(client))
        assertEquals("Custom/1.0", userAgentSentBy(client) { header("User-Agent", "Custom/1.0") })
    }

    @Test
    fun `the database dao providers hand out the database's daos`() {
        assertSame(db.podcastDao(), module.podcastDao(db))
        assertSame(db.episodeDao(), module.episodeDao(db))
        assertSame(db.syncQueueDao(), module.syncQueueDao(db))
        assertSame(db.syncStateDao(), module.syncStateDao(db))
        assertSame(db.subscriptionChangeDao(), module.subscriptionChangeDao(db))
    }

    @Test
    fun `the secret store is backed by the keystore`() {
        assertEquals(KeystoreSecretStore::class, module.secretStore(context)::class)
    }

    @Test
    fun `the download schedulers share one instance`() {
        val settings = module.settings(context, module.secretStore(context))
        val tx = module.transactions(db)
        val clock = module.clock()
        val episodes = module.episodeRepository(module.podcastDao(db), module.episodeDao(db), module.syncQueueDao(db), tx, clock)
        val manager = module.downloadManager(
            context, module.episodeDao(db), episodes, module.okHttpClient(), settings, module.evictionNotices(context),
        )
        val downloads = module.workManagerDownloads(context, manager)

        assertSame(downloads, module.downloadScheduler(downloads))
        assertSame(downloads, module.downloadCanceller(downloads))
    }

    @Test
    fun `the whole object graph can be assembled`() {
        val client = module.okHttpClient()
        val clock = module.clock()
        val secrets = module.secretStore(context)
        val settings = module.settings(context, secrets)
        val tx = module.transactions(db)
        val podcasts = module.podcastDao(db)
        val episodeDao = module.episodeDao(db)
        val queue = module.syncQueueDao(db)
        val changes = module.subscriptionChangeDao(db)
        val episodeRepo = module.episodeRepository(podcasts, episodeDao, queue, tx, clock)
        val manager = module.downloadManager(context, episodeDao, episodeRepo, client, settings, module.evictionNotices(context))
        val downloads = module.workManagerDownloads(context, manager)
        val hub = module.playbackHub()
        val remote = module.remoteSync(settings, client, podcasts, episodeDao, queue, changes, module.syncStateDao(db), tx, clock)
        val subscriptions = module.subscriptionRepository(podcasts, changes, tx, clock)

        val provided = listOf(
            module.podcastRepository(podcasts, episodeDao, tx, clock),
            module.subscriptionsPreferences(context),
            module.feedFetcher(client, settings),
            module.downloadWaits(context),
            module.syncScheduler(context),
            module.syncPipeline(
                remote, podcasts, episodeDao, module.podcastRepository(podcasts, episodeDao, tx, clock),
                module.feedFetcher(client, settings), downloads, manager, module.playbackPort(hub), settings,
            ),
            module.discovery(client, settings, clock),
            module.themeStore(settings),
            module.settingsStore(settings),
            module.syncController(module.syncScheduler(context)),
            module.playQueue(context),
            module.episodeActions(manager, episodeRepo, downloads, downloads),
            module.directorySearch(module.discovery(client, settings, clock)),
            module.loginFlow(client, settings),
            module.opmlTransfer(podcasts, subscriptions, settings),
        )

        provided.forEach { assertNotNull(it) }
        assertSame(hub, module.playbackPort(hub))
    }
}
