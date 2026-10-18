// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.network.discovery

import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.assertThrows
import org.sonora.testutil.MockWebServerExtension
import org.sonora.core.database.MutableClock
import org.sonora.core.model.DirectoryPodcast

class PodcastIndexTest {
    @JvmField @RegisterExtension val web = MockWebServerExtension()
    private val server get() = web.server
    private val clock = MutableClock(nowMillis = 1_700_000_123_456)
    private val credentials = PodcastIndexCredentials("KEY123", "s3cr3t")



    private fun json(body: String, code: Int = 200) = MockResponse.Builder()
        .code(code).addHeader("Content-Type", "application/json").body(body).build()

    private fun sha1Hex(s: String) =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test
    fun `interceptor attaches user agent, key, date and sha1 authorization`() {
        val client = OkHttpClient.Builder()
            .addInterceptor(PodcastIndexAuthInterceptor(credentials, "Sonora/0.1", clock))
            .build()
        server.enqueue(json("{}"))

        client.newCall(Request.Builder().url(server.url("/api/1.0/search/byterm?q=x")).build()).execute().close()

        val request = server.takeRequest()
        assertEquals("Sonora/0.1", request.headers["User-Agent"])
        assertEquals("KEY123", request.headers["X-Auth-Key"])
        assertEquals("1700000123", request.headers["X-Auth-Date"])
        assertEquals(sha1Hex("KEY123s3cr3t1700000123"), request.headers["Authorization"])
        assertTrue(Regex("[0-9a-f]{40}").matches(request.headers["Authorization"]!!))
    }

    @Test
    fun `authorization is recomputed per request from the current time`() {
        val client = OkHttpClient.Builder()
            .addInterceptor(PodcastIndexAuthInterceptor(credentials, "Sonora/0.1", clock))
            .build()
        repeat(2) { server.enqueue(json("{}")) }

        client.newCall(Request.Builder().url(server.url("/a")).build()).execute().close()
        clock.nowMillis += 5_000
        client.newCall(Request.Builder().url(server.url("/b")).build()).execute().close()

        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals("1700000123", first.headers["X-Auth-Date"])
        assertEquals("1700000128", second.headers["X-Auth-Date"])
        assertEquals(sha1Hex("KEY123s3cr3t1700000128"), second.headers["Authorization"])
    }

    @Test
    fun `search by term maps feeds to domain models and signs the request`() = runTest {
        server.enqueue(
            json(
                """{"status":"true","count":2,"query":"linux","feeds":[
                {"id":1,"title":"Linux Show","url":"https://l.example/rss","author":"Ann",
                 "image":"https://l/img.png","artwork":"https://l/art.png"},
                {"id":2,"title":"Bare","url":"https://b.example/rss","image":"","artwork":""}]}""",
            ),
        )
        val provider = PodcastIndexSearchProvider(
            DirectoryApiFactory.podcastIndex(
                baseUrl = server.url("/").toString(),
                credentials = credentials,
                userAgent = "Sonora/0.1",
                clock = clock,
                allowCleartext = true,
            ),
        )

        val results = provider.search("linux show")

        val request = server.takeRequest()
        assertEquals("/api/1.0/search/byterm?q=linux%20show", request.target)
        assertEquals("KEY123", request.headers["X-Auth-Key"])
        assertEquals(
            listOf(
                DirectoryPodcast("Linux Show", "Ann", "https://l.example/rss", "https://l/art.png"),
                DirectoryPodcast("Bare", null, "https://b.example/rss", null),
            ),
            results,
        )
    }

    @Test
    fun `artwork falls back to image`() = runTest {
        server.enqueue(json("""{"status":"true","feeds":[{"title":"T","url":"https://t/rss","image":"https://t/i.png"}]}"""))
        val provider = PodcastIndexSearchProvider(
            DirectoryApiFactory.podcastIndex(server.url("/").toString(), credentials, "ua", clock, allowCleartext = true),
        )
        assertEquals("https://t/i.png", provider.search("t").single().artworkUrl)
    }

    @Test
    fun `auth failure is wrapped in DirectoryException`() = runTest {
        server.enqueue(json("""{"status":"false","description":"Invalid"}""", code = 401))
        val provider = PodcastIndexSearchProvider(
            DirectoryApiFactory.podcastIndex(server.url("/").toString(), credentials, "ua", clock, allowCleartext = true),
        )
        assertThrows<DirectoryException> { provider.search("t") }
    }

    @Test
    fun `refuses cleartext base url unless allowed`() {
        assertThrows<IllegalArgumentException> {
            DirectoryApiFactory.podcastIndex("http://api.podcastindex.org/", credentials, "ua", clock)
        }
    }
}
