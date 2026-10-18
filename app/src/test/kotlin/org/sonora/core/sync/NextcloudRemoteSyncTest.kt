// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.sonora.core.database.FakeEpisodeDao
import org.sonora.core.database.FakePodcastDao
import org.sonora.core.database.FakeSubscriptionChangeDao
import org.sonora.core.database.FakeSyncQueueDao
import org.sonora.core.database.FakeSyncStateDao
import org.sonora.core.database.FakeTransactionRunner
import org.sonora.core.database.MutableClock
import org.sonora.feature.settings.AppSettings
import org.sonora.feature.settings.NextcloudAccount
import org.sonora.testutil.FakeSecretStore

@RunWith(AndroidJUnit4::class)
class NextcloudRemoteSyncTest {
    private val server = MockWebServer()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val settings = AppSettings(context, FakeSecretStore())
    private val podcasts = FakePodcastDao()

    private fun remoteSync() = NextcloudRemoteSync(
        settings = settings, client = OkHttpClient(), podcastDao = podcasts, episodeDao = FakeEpisodeDao(),
        syncQueueDao = FakeSyncQueueDao(), subscriptionChangeDao = FakeSubscriptionChangeDao(),
        syncStateDao = FakeSyncStateDao(), transactions = FakeTransactionRunner(), clock = MutableClock(),
    )

    private fun json(body: String) = MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()

    @Before
    fun setUp() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        server.start()
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `without an account there is nothing to sync`() = runBlocking {
        assertNull(remoteSync().sync())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `syncs against the stored account using its credentials`() = runBlocking {
        settings.allowCleartext = true
        settings.saveNextcloudAccount(NextcloudAccount(server.url("/").toString(), "alice", "app-pass"))
        server.enqueue(json("""{"add":["https://new/feed"],"remove":[],"timestamp":10}"""))
        server.enqueue(json("""{"actions":[],"timestamp":20}"""))

        val report = remoteSync().sync()

        assertEquals(SyncReport(subscriptionsAdded = 1), report)
        assertEquals(listOf("https://new/feed"), podcasts.getAll().map { it.feedUrl })
        // Basic auth for alice:app-pass
        assertEquals("Basic YWxpY2U6YXBwLXBhc3M=", server.takeRequest().headers["Authorization"])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a cleartext server is refused unless the user opted in`() {
        settings.saveNextcloudAccount(NextcloudAccount(server.url("/").toString(), "alice", "app-pass"))

        runBlocking { remoteSync().sync() }
    }
}
