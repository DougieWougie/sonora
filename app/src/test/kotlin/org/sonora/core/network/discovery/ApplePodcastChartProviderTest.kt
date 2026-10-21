// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.network.discovery

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.RegisterExtension
import org.sonora.core.model.DirectoryPodcast
import org.sonora.testutil.MockWebServerExtension

class ApplePodcastChartProviderTest {
    @JvmField @RegisterExtension val web = MockWebServerExtension()
    private val server get() = web.server
    private lateinit var provider: ApplePodcastChartProvider

    @BeforeEach
    fun setUp() {
        val base = server.url("/").toString()
        provider = ApplePodcastChartProvider(
            DirectoryApiFactory.appleCharts(base, allowCleartext = true),
            DirectoryApiFactory.apple(base, allowCleartext = true),
        )
    }

    private fun json(body: String, code: Int = 200) = MockResponse.Builder()
        .code(code).addHeader("Content-Type", "application/json").body(body).build()

    private val chart = """{"feed":{"results":[
        {"id":"11","name":"First","artistName":"A","artworkUrl100":"https://img/11-100.png"},
        {"id":"22","name":"Second","artistName":"B","artworkUrl100":"https://img/22-100.png"},
        {"id":"33","name":"Delisted","artistName":"C"}]}}"""

    @Test
    fun `reads the country chart then looks the shows up to find their feeds`() = runTest {
        server.enqueue(json(chart))
        server.enqueue(
            json(
                """{"results":[
                {"collectionId":22,"collectionName":"Second","artistName":"B","feedUrl":"https://b/feed","artworkUrl600":"https://img/22-600.jpg"},
                {"collectionId":11,"collectionName":"First","artistName":"A","feedUrl":"https://a/feed"}]}""",
            ),
        )

        val top = provider.top("gb", 3)

        assertEquals("/api/v2/gb/podcasts/top/3/podcasts.json", server.takeRequest().target)
        assertEquals("/lookup?entity=podcast&id=11,22,33&country=gb", server.takeRequest().target)
        // Chart order wins over the order lookup answered in; Delisted has no feed so is dropped.
        assertEquals(
            listOf(
                DirectoryPodcast("First", "A", "https://a/feed", "https://img/11-100.png"),
                DirectoryPodcast("Second", "B", "https://b/feed", "https://img/22-600.jpg"),
            ),
            top,
        )
    }

    @Test
    fun `an empty chart does not look anything up`() = runTest {
        server.enqueue(json("""{"feed":{"results":[]}}"""))

        assertTrue(provider.top("gb", 5).isEmpty())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a chart error is wrapped in DirectoryException`() = runTest {
        server.enqueue(json("{}", code = 503))
        assertThrows<DirectoryException> { provider.top("gb", 5) }
    }

    @Test
    fun `an unreadable lookup is wrapped in DirectoryException`() = runTest {
        server.enqueue(json(chart))
        server.enqueue(json("<html>nope</html>"))
        assertThrows<DirectoryException> { provider.top("gb", 5) }
    }
}
