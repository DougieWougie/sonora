// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.parser

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.sonora.core.model.OpmlEntry
import org.xmlpull.v1.XmlPullParserFactory

class OpmlParserTest {
    private val factory = XmlPullParserFactory.newInstance()
    private val parser = OpmlParser(factory)
    private val writer = OpmlWriter(factory)

    @Test
    fun `imports feed outlines including nested ones and ignores non feed outlines`() {
        val entries = javaClass.getResourceAsStream("/fixtures/subscriptions.opml")!!.use { parser.parse(it) }
        assertEquals(
            listOf(
                OpmlEntry("Show A", "https://a.example.org/feed.xml", "https://a.example.org"),
                OpmlEntry("Show B & Friends", "https://b.example.org/rss", null),
                OpmlEntry("Show C", "https://c.example.org/rss", null),
            ),
            entries,
        )
    }

    @Test
    fun `export produces opml 2 that round trips including escaping`() {
        val entries = listOf(
            OpmlEntry("Tom & \"Jerry\" <live>", "https://x.example.org/feed?a=1&b=2", "https://x.example.org"),
            OpmlEntry("Plain", "https://y.example.org/rss", null),
        )
        val out = ByteArrayOutputStream().also { writer.write(entries, "Sonora subscriptions", it) }
        val text = out.toString(Charsets.UTF_8)

        assert(text.contains("""<opml version="2.0">""")) { text }
        assertEquals(entries, parser.parse(ByteArrayInputStream(out.toByteArray())))
    }
}
