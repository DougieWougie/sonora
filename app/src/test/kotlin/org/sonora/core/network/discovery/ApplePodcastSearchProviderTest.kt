// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.network.discovery

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.assertThrows
import org.sonora.testutil.MockWebServerExtension
import org.sonora.core.model.DirectoryPodcast

class ApplePodcastSearchProviderTest {
    @JvmField @RegisterExtension val web = MockWebServerExtension()
    private val server get() = web.server
    private lateinit var provider: ApplePodcastSearchProvider

    @BeforeEach
    fun setUp() {
        provider = ApplePodcastSearchProvider(
            DirectoryApiFactory.apple(server.url("/").toString(), allowCleartext = true),
        )
    }


    private fun json(body: String, code: Int = 200) = MockResponse.Builder()
        .code(code).addHeader("Content-Type", "text/javascript; charset=utf-8").body(body).build()

    @Test
    fun `queries the search endpoint for podcasts and maps results`() = runTest {
        server.enqueue(
            json(
                """{"resultCount":1,"results":[{"wrapperType":"track","collectionName":"Hello Internet",
                "feedUrl":"https://hi.example/feed.xml","artworkUrl600":"https://img/600.jpg",
                "artistName":"CGP Grey & Brady","trackCount":120}]}""",
            ),
        )

        val results = provider.search("hello internet")

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/search?media=podcast&entity=podcast&term=hello%20internet", request.target)
        assertEquals(
            listOf(DirectoryPodcast("Hello Internet", "CGP Grey & Brady", "https://hi.example/feed.xml", "https://img/600.jpg")),
            results,
        )
    }

    @Test
    fun `skips results without a feed url`() = runTest {
        server.enqueue(
            json(
                """{"resultCount":2,"results":[
                {"collectionName":"Delisted","artistName":"x"},
                {"collectionName":"Ok","feedUrl":"https://ok/feed"}]}""",
            ),
        )

        val results = provider.search("q")

        assertEquals(listOf("Ok"), results.map { it.title })
        assertEquals(null, results.single().artworkUrl)
    }

    @Test
    fun `empty result set yields an empty list`() = runTest {
        server.enqueue(json("""{"resultCount":0,"results":[]}"""))
        assertTrue(provider.search("zzzz").isEmpty())
    }

    @Test
    fun `blank query does not hit the network`() = runTest {
        assertTrue(provider.search("   ").isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `http errors are wrapped in DirectoryException`() = runTest {
        server.enqueue(json("{}", code = 503))
        assertThrows<DirectoryException> { provider.search("q") }
    }

    @Test
    fun `malformed json is wrapped in DirectoryException`() = runTest {
        server.enqueue(json("<html>nope</html>"))
        assertThrows<DirectoryException> { provider.search("q") }
    }
}
