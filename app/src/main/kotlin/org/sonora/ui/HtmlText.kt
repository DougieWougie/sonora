// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration
import org.sonora.core.text.MAX_HTML_CHARS
import org.sonora.core.text.MAX_TAG_CHARS
import org.sonora.core.text.withoutBlockedElements

/*
 * Feed descriptions are HTML written by whoever runs the podcast, so nothing here trusts them:
 * scripts, styles and images are dropped before parsing and only web and mail links stay clickable.
 * Input is also cut off at [MAX_HTML_CHARS], and no pattern may scan past [MAX_TAG_CHARS] looking for
 * a closing ">", so a hostile feed cannot make the work grow with the square of its size.
 */

private val IMAGE = Regex("<img\\b[^>]{0,$MAX_TAG_CHARS}>", RegexOption.IGNORE_CASE)
private val LIST_ITEM = Regex("<li\\b[^>]{0,$MAX_TAG_CHARS}>", RegexOption.IGNORE_CASE)
private val BLOCK_END = Regex("</(p|li)\\s*>", RegexOption.IGNORE_CASE)
private val ANY_TAG = Regex("<[a-zA-Z/!][^>]{0,$MAX_TAG_CHARS}>")
private val BLANK_LINES = Regex("\\n(?:[ \\t]*\\n){2,}")
private val SAFE_SCHEMES = setOf("http", "https", "mailto")

/** Plain text for list rows and previews: no tags, entities decoded, paragraphs kept as line breaks. */
fun htmlToPlainText(html: String): String = htmlToAnnotatedString(html).text

/**
 * Show notes with their bold, italics and links kept. Anything Compose text cannot show (images,
 * scripts, styles) is dropped, and links that are not web or mail addresses are left as plain text.
 */
fun htmlToAnnotatedString(html: String, linkStyles: TextLinkStyles? = null): AnnotatedString {
    val parsed = AnnotatedString.fromHtml(prepare(html), linkStyles)
    val safe = buildAnnotatedString {
        append(parsed.text)
        parsed.spanStyles.forEach { addStyle(it.item, it.start, it.end) }
        parsed.paragraphStyles.forEach { addStyle(it.item, it.start, it.end) }
        parsed.getLinkAnnotations(0, parsed.length).forEach { link ->
            val url = link.item as? LinkAnnotation.Url ?: return@forEach
            if (url.isSafe()) addLink(url, link.start, link.end)
        }
    }
    return safe.tidy()
}

/** [htmlToAnnotatedString] with links in the theme's link colour, parsed again only when the input changes. */
@Composable
fun rememberHtml(html: String): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    return remember(html, linkColor) {
        htmlToAnnotatedString(html, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)))
    }
}

private fun prepare(html: String): String {
    val cleaned = html.take(MAX_HTML_CHARS).withoutBlockedElements("").replace(IMAGE, "").replace(LIST_ITEM, "<li>• ")
        // Compose's HTML mode leaves no gap between paragraphs and none at all between list items.
        .replace(BLOCK_END) { "${it.value}<br>" }
    // HTML folds newlines into spaces, which would flatten a plain text description.
    return if (ANY_TAG.containsMatchIn(cleaned)) cleaned else cleaned.replace("\n", "<br>")
}

private fun LinkAnnotation.Url.isSafe(): Boolean =
    url.substringBefore(':', missingDelimiterValue = "").trim().lowercase() in SAFE_SCHEMES

/** Trims the ends and squeezes runs of blank lines, keeping the styles and links on what is left. */
private fun AnnotatedString.tidy(): AnnotatedString {
    val first = text.indexOfFirst { !it.isWhitespace() }
    if (first < 0) return AnnotatedString("")
    val end = text.indexOfLast { !it.isWhitespace() } + 1
    var cursor = first
    return buildAnnotatedString {
        for (run in BLANK_LINES.findAll(text.substring(0, end), first)) {
            append(subSequence(cursor, run.range.first))
            append("\n\n")
            cursor = run.range.last + 1
        }
        append(subSequence(cursor, end))
    }
}
