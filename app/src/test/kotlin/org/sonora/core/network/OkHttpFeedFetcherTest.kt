// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.assertThrows
import org.sonora.testutil.MockWebServerExtension
import org.sonora.core.parser.RssFeedParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory

class OkHttpFeedFetcherTest {
    @JvmField @RegisterExtension val web = MockWebServerExtension()
    private val server get() = web.server
    private lateinit var fetcher: OkHttpFeedFetcher

    @BeforeEach
    fun setUp() {
        fetcher = fetcher(allowCleartext = true)
    }


    private fun fetcher(allowCleartext: Boolean) = OkHttpFeedFetcher(
        client = OkHttpClient(),
        parser = RssFeedParser(XmlPullParserFactory.newInstance()),
        ioDispatcher = Dispatchers.IO,
        userAgent = "Sonora/test",
        allowCleartext = allowCleartext,
    )

    private fun fixtureBytes() = javaClass.getResourceAsStream("/fixtures/podcast.xml")!!.readBytes()

    @Test
    fun `fetches and parses a feed, sending a user agent`() = runTest {
        server.enqueue(MockResponse.Builder().body(okio.Buffer().write(fixtureBytes())).build())

        val feed = fetcher.fetch(server.url("/feed.xml").toString())

        assertTrue(feed.episodes.isNotEmpty())
        assertEquals("Sonora/test", server.takeRequest().headers["User-Agent"])
    }

    @Test
    fun `http errors carry whether they are worth retrying`() = runTest {
        server.enqueue(MockResponse.Builder().code(404).build())
        server.enqueue(MockResponse.Builder().code(503).build())

        val gone = assertThrows<FeedFetchException> { fetcher.fetch(server.url("/a").toString()) }
        val busy = assertThrows<FeedFetchException> { fetcher.fetch(server.url("/b").toString()) }

        assertFalse(gone.transient)
        assertTrue(busy.transient)
    }

    @Test
    fun `non-feed content surfaces the parser exception`() = runTest {
        server.enqueue(MockResponse.Builder().body("<html><body>hi</body></html>").build())

        assertThrows<XmlPullParserException> { fetcher.fetch(server.url("/x").toString()) }
    }

    @Test
    fun `cleartext feeds are refused unless allowed`() = runTest {
        val strict = fetcher(allowCleartext = false)

        val failure = assertThrows<FeedFetchException> { strict.fetch(server.url("/x").toString()) }

        assertFalse(failure.transient)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an oversized body fails as a permanent limit error`() = runTest {
        val small = OkHttpFeedFetcher(
            client = OkHttpClient(),
            parser = RssFeedParser(XmlPullParserFactory.newInstance(), maxBytes = 1_000),
            ioDispatcher = Dispatchers.IO,
            userAgent = "Sonora/test",
            allowCleartext = true,
        )
        server.enqueue(MockResponse.Builder().body(okio.Buffer().write(fixtureBytes().let { it + ByteArray(5_000) { ' '.code.toByte() } })).build())

        assertThrows<org.sonora.core.parser.FeedLimitExceededException> { small.fetch(server.url("/big").toString()) }
    }

    @Test
    fun `a stalled response hits the call timeout as an io failure`() = runTest {
        val impatient = OkHttpFeedFetcher(
            client = OkHttpClient(),
            parser = RssFeedParser(XmlPullParserFactory.newInstance()),
            ioDispatcher = Dispatchers.IO,
            userAgent = "Sonora/test",
            allowCleartext = true,
            callTimeout = java.time.Duration.ofMillis(50),
        )
        server.enqueue(MockResponse.Builder().headersDelay(5, java.util.concurrent.TimeUnit.SECONDS).body("<rss/>").build())

        assertThrows<java.io.IOException> { impatient.fetch(server.url("/slow").toString()) }
    }

    @Test
    fun `fetchConditional sends validators and returns NotModified on 304`() = runTest {
        server.enqueue(MockResponse.Builder().code(304).build())

        val result = fetcher(allowCleartext = true).fetchConditional(
            server.url("/feed").toString(),
            etag = "\"v1\"",
            lastModified = "Wed, 21 Oct 2015 07:28:00 GMT",
        )

        assertEquals(org.sonora.core.sync.FeedFetchResult.NotModified, result)
        val req = server.takeRequest()
        assertEquals("\"v1\"", req.headers["If-None-Match"])
        assertEquals("Wed, 21 Oct 2015 07:28:00 GMT", req.headers["If-Modified-Since"])
    }

    @Test
    fun `fetchConditional captures ETag and Last-Modified on 200`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .addHeader("ETag", "\"v2\"")
                .addHeader("Last-Modified", "Thu, 22 Oct 2015 07:28:00 GMT")
                .body(okio.Buffer().write(fixtureBytes()))
                .build(),
        )

        val result = fetcher(allowCleartext = true).fetchConditional(server.url("/feed").toString(), etag = null, lastModified = null)
            as org.sonora.core.sync.FeedFetchResult.Updated

        assertEquals("\"v2\"", result.etag)
        assertEquals("Thu, 22 Oct 2015 07:28:00 GMT", result.lastModified)
        assertEquals("Example Show", result.feed.title)
    }
}
