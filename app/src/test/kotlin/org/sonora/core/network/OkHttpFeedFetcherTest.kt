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
}
