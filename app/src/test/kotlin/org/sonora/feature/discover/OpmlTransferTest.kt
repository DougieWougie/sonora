// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.discover

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.sonora.core.database.FakePodcastDao
import org.sonora.core.database.FakeSubscriptionChangeDao
import org.sonora.core.database.FakeTransactionRunner
import org.sonora.core.database.MutableClock
import org.sonora.core.database.PodcastEntity
import org.sonora.core.database.SubscriptionRepository
import org.sonora.core.parser.OpmlParser
import org.sonora.core.parser.OpmlWriter
import org.xmlpull.v1.XmlPullParserFactory

class OpmlTransferTest {
    private val podcasts = FakePodcastDao()
    private val changes = FakeSubscriptionChangeDao()
    private val factory = XmlPullParserFactory.newInstance()
    private val transfer = DefaultOpmlTransfer(
        parser = OpmlParser(factory),
        writer = OpmlWriter(factory),
        podcastDao = podcasts,
        subscriptions = SubscriptionRepository(podcasts, changes, FakeTransactionRunner(), MutableClock()),
    )

    private fun opml(vararg feeds: Pair<String, String>) = buildString {
        append("<opml version=\"2.0\"><body>")
        feeds.forEach { (t, u) -> append("<outline type=\"rss\" text=\"$t\" xmlUrl=\"$u\"/>") }
        append("</body></opml>")
    }.byteInputStream()

    @Test
    fun `import subscribes to every feed and records the sync intent`() = runTest {
        val result = transfer.import(opml("A" to "https://a/feed", "B" to "https://b/feed"))

        assertEquals(OpmlImportResult(imported = 2, alreadySubscribed = 0), result)
        assertEquals(setOf("https://a/feed", "https://b/feed"), podcasts.rows.value.map { it.feedUrl }.toSet())
        assertEquals(setOf("https://a/feed", "https://b/feed"), changes.rows.keys)
    }

    @Test
    fun `import skips feeds that are already subscribed`() = runTest {
        podcasts.insert(PodcastEntity(feedUrl = "https://a/feed", title = "A"))

        val result = transfer.import(opml("A" to "https://a/feed", "B" to "https://b/feed"))

        assertEquals(OpmlImportResult(imported = 1, alreadySubscribed = 1), result)
    }

    @Test
    fun `import refuses cleartext feeds unless allowed`() = runTest {
        val result = transfer.import(opml("A" to "http://a/feed", "B" to "https://b/feed"))

        assertEquals(OpmlImportResult(imported = 1, alreadySubscribed = 0, rejected = 1), result)
        assertEquals(listOf("https://b/feed"), podcasts.rows.value.map { it.feedUrl })
    }

    @Test
    fun `import fails on malformed xml without subscribing anything`() = runTest {
        assertThrows<Exception> { transfer.import(ByteArrayInputStream("<opml><body><outline".toByteArray())) }
        assertTrue(podcasts.rows.value.isEmpty())
    }

    @Test
    fun `export writes every subscription and round-trips through the parser`() = runTest {
        podcasts.insert(PodcastEntity(feedUrl = "https://a/feed", title = "A"))
        podcasts.insert(PodcastEntity(feedUrl = "https://b/feed", title = "B & C"))
        val out = ByteArrayOutputStream()

        assertEquals(2, transfer.export(out))

        val parsed = OpmlParser(factory).parse(ByteArrayInputStream(out.toByteArray()))
        assertEquals(listOf("https://a/feed", "https://b/feed"), parsed.map { it.feedUrl })
        assertEquals("B & C", parsed[1].title)
    }
}
