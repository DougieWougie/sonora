// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.parser

import java.io.InputStream
import java.io.OutputStream
import org.sonora.core.model.OpmlEntry
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/** Reads every `<outline>` that carries an `xmlUrl`, regardless of how deeply it is nested in folders. */
class OpmlParser(private val factory: XmlPullParserFactory) {

    fun parse(input: InputStream): List<OpmlEntry> {
        val parser = newHardenedParser(factory)
        parser.setInput(input, null)
        val entries = mutableListOf<OpmlEntry>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG || parser.name != "outline") continue
            val feedUrl = parser.attr("xmlUrl") ?: continue
            val title = parser.attr("title") ?: parser.attr("text") ?: feedUrl
            entries += OpmlEntry(title, feedUrl, parser.attr("htmlUrl"))
        }
        return entries
    }

    private fun XmlPullParser.attr(name: String): String? =
        (0 until attributeCount)
            .firstOrNull { getAttributeName(it).equals(name, ignoreCase = true) }
            ?.let { getAttributeValue(it).trim().ifEmpty { null } }
}

class OpmlWriter(private val factory: XmlPullParserFactory) {

    fun write(entries: List<OpmlEntry>, title: String, out: OutputStream) {
        val s = factory.newSerializer()
        s.setOutput(out, "UTF-8")
        s.startDocument("UTF-8", null)
        s.startTag(null, "opml")
        s.attribute(null, "version", "2.0")
        s.startTag(null, "head")
        s.startTag(null, "title")
        s.text(title)
        s.endTag(null, "title")
        s.endTag(null, "head")
        s.startTag(null, "body")
        for (entry in entries) {
            s.startTag(null, "outline")
            s.attribute(null, "type", "rss")
            s.attribute(null, "text", entry.title)
            s.attribute(null, "title", entry.title)
            s.attribute(null, "xmlUrl", entry.feedUrl)
            entry.htmlUrl?.let { s.attribute(null, "htmlUrl", it) }
            s.endTag(null, "outline")
        }
        s.endTag(null, "body")
        s.endTag(null, "opml")
        s.endDocument()
        s.flush()
    }
}
