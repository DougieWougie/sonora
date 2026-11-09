// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.parser

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Creates a pull parser hardened against XXE: DOCTYPE processing (and therefore external
 * entity declarations) is switched off. Parsers that cannot toggle the feature never
 * resolve external entities anyway, so a failure to set it is not fatal.
 */
internal fun newHardenedParser(factory: XmlPullParserFactory): XmlPullParser {
    factory.isNamespaceAware = true
    factory.isValidating = false
    return factory.newPullParser().also {
        try {
            it.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        } catch (_: XmlPullParserException) {
        }
    }
}

/**
 * Reads the text content of the current element and leaves the parser on its end tag.
 * Throws [FeedLimitExceededException] if it runs past [maxChars].
 */
internal fun XmlPullParser.readText(maxChars: Int = FeedLimits.MAX_FIELD_CHARS): String {
    val text = StringBuilder()
    var depth = 1
    while (depth > 0) {
        when (next()) {
            XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF ->
                if (depth == 1) {
                    text.append(this.text.orEmpty())
                    if (text.length > maxChars) throw FeedLimitExceededException("Text field is longer than $maxChars characters")
                }
            XmlPullParser.START_TAG -> depth++
            XmlPullParser.END_TAG -> depth--
            XmlPullParser.END_DOCUMENT -> throw XmlPullParserException("Unexpected end of document", this, null)
        }
    }
    return text.toString().trim()
}

/** Skips the current element and all its children, leaving the parser on its end tag. */
internal fun XmlPullParser.skipElement() {
    var depth = 1
    while (depth > 0) {
        when (next()) {
            XmlPullParser.START_TAG -> depth++
            XmlPullParser.END_TAG -> depth--
            XmlPullParser.END_DOCUMENT -> throw XmlPullParserException("Unexpected end of document", this, null)
        }
    }
}
