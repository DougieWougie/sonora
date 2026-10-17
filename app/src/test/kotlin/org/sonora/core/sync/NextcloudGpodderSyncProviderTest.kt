// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.sync

import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Credentials
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.sonora.core.model.SyncActionType
import org.sonora.core.network.NextcloudApiFactory

class NextcloudGpodderSyncProviderTest {
    private val server = MockWebServer()
    private lateinit var provider: NextcloudGpodderSyncProvider

    @BeforeEach
    fun setUp() {
        server.start()
        val api = NextcloudApiFactory.create(server.url("/").toString(), "alice", "app-pass", allowCleartext = true)
        provider = NextcloudGpodderSyncProvider(api)
    }

    @AfterEach
    fun tearDown() = server.close()

    private fun json(body: String) = MockResponse.Builder()
        .addHeader("Content-Type", "application/json").body(body).build()

    @Test
    fun `pulls subscription changes since a timestamp using basic auth`() = runTest {
        server.enqueue(
            json("""{"add":["https://a/feed"],"remove":["https://b/feed"],"timestamp":1663540502}"""),
        )

        val changes = provider.pullSubscriptions(since = 1633240761)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/index.php/apps/gpoddersync/subscriptions?since=1633240761", request.target)
        assertEquals(Credentials.basic("alice", "app-pass"), request.headers["Authorization"])
        assertEquals(SubscriptionChanges(listOf("https://a/feed"), listOf("https://b/feed"), 1663540502), changes)
    }

    @Test
    fun `pushes subscription changes as add and remove arrays`() = runTest {
        server.enqueue(json("""{"timestamp":777}"""))

        val ts = provider.pushSubscriptions(add = listOf("https://a/feed"), remove = listOf("https://b/feed"))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/index.php/apps/gpoddersync/subscription_change/create", request.target)
        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals(listOf("https://a/feed"), body.getValue("add").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("https://b/feed"), body.getValue("remove").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(777L, ts)
    }

    @Test
    fun `pulls episode actions mapping timestamps, missing numbers and action case`() = runTest {
        server.enqueue(
            json(
                """{"actions":[
                  {"podcast":"https://f","episode":"https://f/1.mp3","guid":"g1","action":"PLAY",
                   "timestamp":"2009-12-12T09:00:00","started":15,"position":120,"total":500},
                  {"podcast":"https://f","episode":"https://f/1.mp3","guid":"g1","action":"download",
                   "timestamp":"2009-12-12T09:00:01","started":-1,"position":-1,"total":-1},
                  {"podcast":"https://f","episode":"https://f/1.mp3","action":"flattr",
                   "timestamp":"2009-12-12T09:00:02"}
                ],"timestamp":12345}""",
            ),
        )

        val page = provider.pullEpisodeActions(since = 0)

        assertEquals("/index.php/apps/gpoddersync/episode_action?since=0", server.takeRequest().target)
        assertEquals(12345L, page.timestamp)
        val utc = Instant.parse("2009-12-12T09:00:00Z").toEpochMilli()
        assertEquals(
            listOf(
                RemoteEpisodeAction("https://f", "https://f/1.mp3", "g1", SyncActionType.PLAY, utc, 15, 120, 500),
                RemoteEpisodeAction("https://f", "https://f/1.mp3", "g1", SyncActionType.DOWNLOAD, utc + 1000),
            ),
            page.actions,
        )
    }

    @Test
    fun `pushes episode actions as a json array with utc iso timestamps`() = runTest {
        server.enqueue(json("""{"timestamp":900}"""))
        val ts = Instant.parse("2024-03-04T05:06:07Z").toEpochMilli()

        val serverTs = provider.pushEpisodeActions(
            listOf(
                RemoteEpisodeAction("https://f", "https://f/1.mp3", "g1", SyncActionType.PLAY, ts, 10, 95, 600),
                RemoteEpisodeAction("https://f", "https://f/2.mp3", null, SyncActionType.DELETE, ts),
            ),
        )

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/index.php/apps/gpoddersync/episode_action/create", request.target)
        val body: JsonArray = Json.parseToJsonElement(request.body!!.utf8()).jsonArray
        val play: JsonObject = body[0].jsonObject
        assertEquals("PLAY", play.getValue("action").jsonPrimitive.content)
        assertEquals("2024-03-04T05:06:07", play.getValue("timestamp").jsonPrimitive.content)
        assertEquals("https://f/1.mp3", play.getValue("episode").jsonPrimitive.content)
        assertEquals("95", play.getValue("position").jsonPrimitive.content)
        assertEquals("10", play.getValue("started").jsonPrimitive.content)
        assertEquals("600", play.getValue("total").jsonPrimitive.content)
        val delete = body[1].jsonObject
        assertEquals("DELETE", delete.getValue("action").jsonPrimitive.content)
        assertFalse(delete.containsKey("guid"))
        assertEquals(900L, serverTs)
    }

    @Test
    fun `rejected credentials surface as SyncAuthException`() = runTest {
        server.enqueue(MockResponse.Builder().code(401).build())
        assertThrows<SyncAuthException> { provider.pullSubscriptions(0) }
    }

    @Test
    fun `server errors surface as SyncException`() = runTest {
        server.enqueue(MockResponse.Builder().code(500).build())
        val ex = assertThrows<SyncException> { provider.pullEpisodeActions(0) }
        assertFalse(ex is SyncAuthException)
        assertTrue(ex.message!!.contains("500"))
    }


    @Test
    fun `cleartext server urls are refused unless explicitly allowed`() {
        assertThrows<IllegalArgumentException> {
            NextcloudApiFactory.create(server.url("/").toString(), "alice", "app-pass")
        }
    }
}
