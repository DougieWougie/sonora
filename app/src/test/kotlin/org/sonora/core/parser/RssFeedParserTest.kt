// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.parser

import java.io.ByteArrayInputStream
import java.io.File
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.sonora.core.model.ParsedFeed
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory

class RssFeedParserTest {
    private val parser = RssFeedParser(XmlPullParserFactory.newInstance())

    private fun fixture(name: String): ParsedFeed =
        javaClass.getResourceAsStream("/fixtures/$name")!!.use { parser.parse(it) }

    @Test
    fun `parses channel metadata including itunes image`() {
        val feed = fixture("podcast.xml")
        assertEquals("Example Show", feed.title)
        assertEquals("A show about & things", feed.description)
        assertEquals("https://example.org/show", feed.link)
        assertEquals("Jane Host", feed.author)
        assertEquals("https://example.org/art/show.jpg", feed.imageUrl)
    }

    @Test
    fun `falls back to rss image url when itunes image is missing`() {
        val feed = fixture("podcast_plain_image.xml")
        assertEquals("https://example.org/plain.png", feed.imageUrl)
    }

    @Test
    fun `skips items without enclosure`() {
        val feed = fixture("podcast.xml")
        assertEquals(4, feed.episodes.size)
        assertFalse(feed.episodes.any { it.guid == "text-only" })
    }

    @Test
    fun `parses the episode page link`() {
        assertEquals("https://example.org/show/episodes/3", fixture("podcast.xml").episodes[0].link)
    }

    @Test
    fun `episode link is null when the item has none`() {
        assertNull(fixture("podcast.xml").episodes[1].link)
    }

    @Test
    fun `parses full episode`() {
        val ep = fixture("podcast.xml").episodes[0]
        assertEquals("ep-3-guid", ep.guid)
        assertEquals("Episode 3: Full", ep.title)
        assertEquals("Third episode", ep.description)
        assertEquals("https://cdn.example.org/ep3.mp3", ep.enclosureUrl)
        assertEquals("audio/mpeg", ep.mimeType)
        assertEquals(12_345_678L, ep.sizeBytes)
        assertEquals(3723L, ep.durationSeconds)
        assertEquals("https://example.org/art/ep3.jpg", ep.imageUrl)
        assertEquals(Instant.parse("2025-06-03T08:30:00Z").toEpochMilli(), ep.publishedAtMillis)
    }

    @Test
    fun `parses mm ss and plain seconds durations`() {
        val eps = fixture("podcast.xml").episodes
        assertEquals(2710L, eps[1].durationSeconds)
        assertEquals(754L, eps[2].durationSeconds)
    }

    @Test
    fun `parses gmt pubDate`() {
        val ep = fixture("podcast.xml").episodes[1]
        assertEquals(Instant.parse("2025-06-02T08:00:00Z").toEpochMilli(), ep.publishedAtMillis)
    }

    @Test
    fun `malformed pubDate yields null instead of failing`() {
        val ep = fixture("podcast.xml").episodes[2]
        assertNull(ep.publishedAtMillis)
        assertEquals("ep-1-guid", ep.guid)
    }

    @Test
    fun `pubDate without weekday is accepted`() {
        val ep = fixture("podcast_plain_image.xml").episodes.single()
        assertEquals(Instant.parse("2024-01-15T12:00:00Z").toEpochMilli(), ep.publishedAtMillis)
    }

    @Test
    fun `missing guid falls back to enclosure url and bad numbers become null`() {
        val ep = fixture("podcast.xml").episodes[3]
        assertEquals("https://cdn.example.org/noguid.mp3", ep.guid)
        assertNull(ep.durationSeconds)
        assertNull(ep.sizeBytes)
    }

    @Test
    fun `does not resolve external entities`(@TempDir dir: File) {
        val secret = File(dir, "secret.txt").apply { writeText("TOP-SECRET-CONTENT") }
        val xml = """<?xml version="1.0"?>
            <!DOCTYPE rss [<!ENTITY xxe SYSTEM "file://${secret.absolutePath}">]>
            <rss version="2.0"><channel><title>&xxe;</title></channel></rss>""".trimIndent()

        // Either rejecting the document or ignoring the entity is acceptable; leaking is not.
        val title = try {
            parser.parse(ByteArrayInputStream(xml.toByteArray())).title
        } catch (_: XmlPullParserException) {
            ""
        }

        assertFalse(title.contains("TOP-SECRET"))
    }

    @Test
    fun `rejects document that is not xml`() {
        assertThrows(XmlPullParserException::class.java) {
            parser.parse(ByteArrayInputStream("<html><body>".toByteArray()))
        }
    }

    private fun parseItems(items: String): ParsedFeed {
        val xml = """<?xml version="1.0"?>
            <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/"><channel><title>T</title>$items</channel></rss>"""
        return parser.parse(ByteArrayInputStream(xml.toByteArray()))
    }

    @Test
    fun `prefers content encoded show notes over the short description`() {
        val feed = parseItems(
            """<item><title>E</title><description>Short</description>
               <content:encoded><![CDATA[<p>Long <b>show notes</b></p>]]></content:encoded>
               <enclosure url="https://e/1.mp3"/></item>""",
        )
        assertEquals("<p>Long <b>show notes</b></p>", feed.episodes.single().description)
    }

    @Test
    fun `prefers content encoded regardless of element order`() {
        val feed = parseItems(
            """<item><title>E</title>
               <content:encoded>Long notes</content:encoded><description>Short</description>
               <enclosure url="https://e/1.mp3"/></item>""",
        )
        assertEquals("Long notes", feed.episodes.single().description)
    }

    @Test
    fun `falls back to the description when content encoded is empty`() {
        val feed = parseItems(
            """<item><title>E</title><description>Short</description><content:encoded> </content:encoded>
               <enclosure url="https://e/1.mp3"/></item>""",
        )
        assertEquals("Short", feed.episodes.single().description)
    }

    // --- limits on untrusted input ---------------------------------------------------------------

    private fun rss(items: String, channelExtra: String = "") =
        """<?xml version="1.0"?><rss version="2.0"><channel><title>T</title>$channelExtra$items</channel></rss>"""

    private fun item(i: Int) = """<item><guid>g$i</guid><title>t$i</title><enclosure url="https://c/$i.mp3" type="audio/mpeg"/></item>"""

    private fun RssFeedParser.parseText(xml: String) = parse(ByteArrayInputStream(xml.toByteArray()))

    @Test
    fun `a single text field over the cap is rejected`() {
        val limited = RssFeedParser(XmlPullParserFactory.newInstance(), maxFieldChars = 100)

        assertThrows(FeedLimitExceededException::class.java) {
            limited.parseText(rss("", channelExtra = "<description>${"x".repeat(101)}</description>"))
        }
        assertEquals(100, limited.parseText(rss("", channelExtra = "<description>${"x".repeat(100)}</description>")).description!!.length)
    }

    @Test
    fun `more items than the cap is rejected`() {
        val limited = RssFeedParser(XmlPullParserFactory.newInstance(), maxItems = 5)

        assertThrows(FeedLimitExceededException::class.java) { limited.parseText(rss((1..6).joinToString("") { item(it) })) }
        assertEquals(5, limited.parseText(rss((1..5).joinToString("") { item(it) })).episodes.size)
    }

    @Test
    fun `a body that decompresses past the byte cap is rejected while streaming`() {
        val limited = RssFeedParser(XmlPullParserFactory.newInstance(), maxBytes = 2_000)

        assertThrows(FeedLimitExceededException::class.java) { limited.parseText(rss((1..100).joinToString("") { item(it) })) }
    }

    @Test
    fun `the default limits are generous`() {
        assertEquals(50L * 1024 * 1024, FeedLimits.MAX_BYTES)
        assertEquals(200_000, FeedLimits.MAX_FIELD_CHARS)
        assertEquals(20_000, FeedLimits.MAX_ITEMS)
    }
}
