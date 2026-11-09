// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.text

/*
 * Feed descriptions are HTML written by whoever runs the podcast. Input is cut off at [MAX_HTML_CHARS],
 * and no pattern may scan past [MAX_TAG_CHARS] looking for a closing ">", so a hostile feed cannot make
 * the work grow with the square of its size. `ui/HtmlText.kt` builds the displayed text on the same rules.
 */

internal const val MAX_HTML_CHARS = 100_000
internal const val MAX_TAG_CHARS = 2_000
private val BLOCKED_ELEMENTS = listOf("script", "style")
private val BLOCK_TAG = Regex("</?(p|br|li|ul|ol|div|h[1-6]|tr|blockquote)\\b[^>]{0,$MAX_TAG_CHARS}>", RegexOption.IGNORE_CASE)
private val TAG = Regex("<[^>]{0,$MAX_TAG_CHARS}>")
private val NUMERIC_ENTITY = Regex("&#(\\d{1,7});")
private val WHITESPACE = Regex("\\s+")

/** Text to match a search against: tags removed and the common entities decoded, without Android classes. */
fun htmlToSearchText(html: String): String =
    html.take(MAX_HTML_CHARS)
        .withoutBlockedElements(" ")
        .replace(BLOCK_TAG, " ")
        .replace(TAG, "")
        .replace("&nbsp;", " ")
        .replace(NUMERIC_ENTITY) { it.groupValues[1].toInt().takeIf(Character::isValidCodePoint)?.let(Character::toString) ?: it.value }
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
        .replace(WHITESPACE, " ")
        .trim()

/**
 * Drops each script and style element with its content in a single pass. An element that is never
 * closed takes the rest of the input with it, rather than leaving its code to show up as text.
 */
internal fun String.withoutBlockedElements(replacement: String): String {
    val out = StringBuilder(length)
    var i = 0
    while (i < length) {
        val open = indexOf('<', i)
        if (open < 0) {
            out.append(this, i, length)
            break
        }
        out.append(this, i, open)
        val name = BLOCKED_ELEMENTS.firstOrNull { name ->
            regionMatches(open + 1, name, 0, name.length, ignoreCase = true) &&
                getOrNull(open + 1 + name.length).let { it == null || !(it.isLetterOrDigit() || it == '_') }
        }
        if (name == null) {
            out.append('<')
            i = open + 1
            continue
        }
        out.append(replacement)
        val close = indexOf("</$name", open + 1, ignoreCase = true)
        val end = if (close < 0) -1 else indexOf('>', close)
        i = if (end < 0) length else end + 1
    }
    return out.toString()
}
