// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.parser

import java.io.InputStream
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import org.sonora.core.model.ParsedEpisode
import org.sonora.core.model.ParsedFeed
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory

/** Streaming RSS 2.0 / iTunes parser. Items without an enclosure are not playable and are skipped. */
class RssFeedParser(private val factory: XmlPullParserFactory) {

    fun parse(input: InputStream): ParsedFeed {
        val parser = newHardenedParser(factory)
        parser.setInput(input, null)

        while (parser.next() != XmlPullParser.START_TAG) {
            if (parser.eventType == XmlPullParser.END_DOCUMENT) throw notRss(parser)
        }
        if (parser.name != "rss") throw notRss(parser)

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "channel") {
                return parseChannel(parser)
            }
        }
        throw notRss(parser)
    }

    private fun notRss(parser: XmlPullParser) =
        XmlPullParserException("Document is not an RSS feed", parser, null)

    private fun parseChannel(p: XmlPullParser): ParsedFeed {
        var title = ""
        var description: String? = null
        var link: String? = null
        var author: String? = null
        var itunesImage: String? = null
        var rssImage: String? = null
        val episodes = mutableListOf<ParsedEpisode>()

        while (p.next() != XmlPullParser.END_TAG) {
            if (p.eventType == XmlPullParser.END_DOCUMENT) throw notRss(p)
            if (p.eventType != XmlPullParser.START_TAG) continue
            when {
                p.name == "title" && p.namespace.isEmpty() -> title = p.readText()
                p.name == "link" && p.namespace.isEmpty() -> link = p.readText().ifEmpty { null }
                p.name == "description" -> description = p.readText().ifEmpty { null }
                p.name == "summary" && p.namespace == ITUNES_NS -> {
                    val summary = p.readText().ifEmpty { null }
                    description = description ?: summary
                }
                p.name == "author" && p.namespace == ITUNES_NS -> author = p.readText().ifEmpty { null }
                p.name == "image" && p.namespace == ITUNES_NS -> {
                    itunesImage = p.getAttributeValue(null, "href")
                    p.skipElement()
                }
                p.name == "image" -> rssImage = parseRssImage(p)
                p.name == "item" -> parseItem(p)?.let(episodes::add)
                else -> p.skipElement()
            }
        }
        return ParsedFeed(title, description, link, author, itunesImage ?: rssImage, episodes)
    }

    private fun parseRssImage(p: XmlPullParser): String? {
        var url: String? = null
        while (p.next() != XmlPullParser.END_TAG) {
            if (p.eventType != XmlPullParser.START_TAG) continue
            if (p.name == "url") url = p.readText().ifEmpty { null } else p.skipElement()
        }
        return url
    }

    private fun parseItem(p: XmlPullParser): ParsedEpisode? {
        var guid: String? = null
        var title = ""
        var description: String? = null
        var showNotes: String? = null
        var enclosureUrl: String? = null
        var mimeType: String? = null
        var size: Long? = null
        var duration: Long? = null
        var published: Long? = null
        var image: String? = null

        while (p.next() != XmlPullParser.END_TAG) {
            if (p.eventType == XmlPullParser.END_DOCUMENT) throw notRss(p)
            if (p.eventType != XmlPullParser.START_TAG) continue
            when {
                p.name == "guid" -> guid = p.readText().ifEmpty { null }
                p.name == "title" && p.namespace.isEmpty() -> title = p.readText()
                p.name == "description" -> description = p.readText().ifEmpty { null }
                p.name == "summary" && p.namespace == ITUNES_NS -> {
                    val summary = p.readText().ifEmpty { null }
                    description = description ?: summary
                }
                p.name == "encoded" && p.namespace == CONTENT_NS -> showNotes = p.readText().ifEmpty { null }
                p.name == "pubDate" -> published = parseDate(p.readText())
                p.name == "duration" && p.namespace == ITUNES_NS -> duration = parseDuration(p.readText())
                p.name == "image" && p.namespace == ITUNES_NS -> {
                    image = p.getAttributeValue(null, "href")
                    p.skipElement()
                }
                p.name == "enclosure" -> {
                    enclosureUrl = p.getAttributeValue(null, "url")?.trim()?.ifEmpty { null }
                    mimeType = p.getAttributeValue(null, "type")
                    size = p.getAttributeValue(null, "length")?.trim()?.toLongOrNull()
                    p.skipElement()
                }
                else -> p.skipElement()
            }
        }
        val url = enclosureUrl ?: return null
        return ParsedEpisode(guid ?: url, title, showNotes ?: description, url, mimeType, size, duration, published, image)
    }

    internal companion object {
        const val ITUNES_NS = "http://www.itunes.com/dtds/podcast-1.0.dtd"
        const val CONTENT_NS = "http://purl.org/rss/1.0/modules/content/"

        /** Returns epoch millis, or null for anything that is not a recognisable date. */
        fun parseDate(raw: String): Long? {
            val text = raw.trim()
            if (text.isEmpty()) return null
            return try {
                ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
                try {
                    OffsetDateTime.parse(text).toInstant().toEpochMilli()
                } catch (_: DateTimeParseException) {
                    null
                }
            }
        }

        /** Accepts `SS`, `MM:SS` and `HH:MM:SS`. */
        fun parseDuration(raw: String): Long? {
            val parts = raw.trim().split(':')
            if (parts.size !in 1..3) return null
            val numbers = parts.map { it.toLongOrNull()?.takeIf { n -> n >= 0 } ?: return null }
            return numbers.fold(0L) { acc, n -> acc * 60 + n }
        }
    }
}
