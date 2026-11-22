// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.sync

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.RegisterExtension
import org.sonora.core.database.FakeEpisodeDao
import org.sonora.core.database.FakePodcastDao
import org.sonora.core.database.FakeSubscriptionChangeDao
import org.sonora.core.database.FakeSyncQueueDao
import org.sonora.core.database.FakeSyncStateDao
import org.sonora.core.database.FakeTransactionRunner
import org.sonora.core.database.MutableClock
import org.sonora.core.settings.NextcloudAccount
import org.sonora.testutil.FakeSettingsStore
import org.sonora.testutil.MockWebServerExtension

class NextcloudRemoteSyncTest {
    @JvmField @RegisterExtension val http = MockWebServerExtension()
    private val server get() = http.server
    private val settings = FakeSettingsStore()
    private val podcasts = FakePodcastDao()

    private fun remoteSync() = NextcloudRemoteSync(
        settings = settings, client = OkHttpClient(), podcastDao = podcasts, episodeDao = FakeEpisodeDao(),
        syncQueueDao = FakeSyncQueueDao(), subscriptionChangeDao = FakeSubscriptionChangeDao(),
        syncStateDao = FakeSyncStateDao(), transactions = FakeTransactionRunner(), clock = MutableClock(),
    )

    private fun json(body: String) = MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()

    @Test
    fun `without an account there is nothing to sync`() = runTest {
        assertNull(remoteSync().sync())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `syncs against the stored account using its credentials`() = runTest {
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

    @Test
    fun `a cleartext server is refused unless the user opted in`() = runTest {
        settings.saveNextcloudAccount(NextcloudAccount(server.url("/").toString(), "alice", "app-pass"))

        assertThrows<IllegalArgumentException> { remoteSync().sync() }
    }
}
