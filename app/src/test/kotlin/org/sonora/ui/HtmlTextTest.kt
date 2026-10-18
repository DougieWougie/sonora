// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.ui

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontWeight
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Android's HTML parser needs Robolectric; these run on the JVM through the vintage engine. */
@RunWith(AndroidJUnit4::class)
class HtmlTextTest {
    // --- plain text ----------------------------------------------------------------------------

    @Test
    fun `plain text passes through unchanged`() {
        assertEquals("Just a sentence.", htmlToPlainText("Just a sentence."))
    }

    @Test
    fun `strips tags and keeps their text`() {
        assertEquals("Hello bold world", htmlToPlainText("<p>Hello <b>bold</b> <i>world</i></p>"))
    }

    @Test
    fun `decodes entities`() {
        assertEquals("Tom & Jerry <3 \"quotes\"", htmlToPlainText("Tom &amp; Jerry &lt;3 &quot;quotes&quot;"))
    }

    @Test
    fun `keeps an ampersand that is not an entity`() {
        assertEquals("Q&A with Ann", htmlToPlainText("Q&A with Ann"))
    }

    @Test
    fun `line breaks and paragraphs become newlines`() {
        assertEquals("One\nTwo", htmlToPlainText("One<br>Two"))
        assertEquals("One\n\nTwo", htmlToPlainText("<p>One</p><p>Two</p>"))
    }

    @Test
    fun `list items land on their own bulleted lines`() {
        assertEquals("• apples\n• pears", htmlToPlainText("<ul><li>apples</li><li>pears</li></ul>"))
    }

    @Test
    fun `keeps link text and drops the url`() {
        assertEquals("Visit our site today", htmlToPlainText("Visit <a href=\"https://example.org\">our site</a> today"))
    }

    @Test
    fun `keeps the newlines of a plain text description`() {
        assertEquals("Line one\nLine two", htmlToPlainText("Line one\nLine two"))
    }

    @Test
    fun `drops script and style content and images`() {
        val html = "<style>p { color: red }</style>Before<script>alert(1)</script><img src=\"https://x/y.png\"> after"
        assertEquals("Before after", htmlToPlainText(html))
    }

    @Test
    fun `trims and collapses runs of blank lines`() {
        assertEquals("One\n\nTwo", htmlToPlainText("  <p>One</p><br><br><br><p>Two</p>  "))
    }

    @Test
    fun `blank input gives blank output`() {
        assertEquals("", htmlToPlainText("   "))
    }

    // --- rich text -----------------------------------------------------------------------------

    @Test
    fun `rich text has the same words as the plain text`() {
        val html = "<p>Hello <b>bold</b> world</p>"
        assertEquals(htmlToPlainText(html), htmlToAnnotatedString(html).text)
    }

    @Test
    fun `rich text keeps bold`() {
        val rich = htmlToAnnotatedString("Hello <b>bold</b> world")
        val bold = rich.spanStyles.single { it.item.fontWeight == FontWeight.Bold }
        assertEquals("bold", rich.text.substring(bold.start, bold.end))
    }

    @Test
    fun `rich text turns anchors into clickable links`() {
        val rich = htmlToAnnotatedString("See <a href=\"https://example.org/notes\">the notes</a>.")
        val link = rich.getLinkAnnotations(0, rich.length).single()
        assertEquals("the notes", rich.text.substring(link.start, link.end))
        assertEquals("https://example.org/notes", (link.item as LinkAnnotation.Url).url)
    }

    @Test
    fun `rich text only links web and mail urls`() {
        val rich = htmlToAnnotatedString("<a href=\"javascript:alert(1)\">x</a> <a href=\"mailto:a@b.org\">mail</a> <a href=\"intent://evil\">y</a>")
        val urls = rich.getLinkAnnotations(0, rich.length).map { (it.item as LinkAnnotation.Url).url }
        assertEquals(listOf("mailto:a@b.org"), urls)
    }

    @Test
    fun `rich text drops images and scripts`() {
        val rich = htmlToAnnotatedString("A<img src=\"https://x/y.png\"><script>alert(1)</script>B")
        assertEquals("AB", rich.text)
        assertFalse(rich.text.contains('￼'))
    }

    @Test
    fun `plain text input stays a single unstyled run`() {
        val rich = htmlToAnnotatedString("Nothing fancy")
        assertEquals("Nothing fancy", rich.text)
        assertTrue(rich.getLinkAnnotations(0, rich.length).isEmpty())
    }

    // --- hostile input -------------------------------------------------------------------------

    @Test
    fun `an unclosed script swallows the rest instead of leaking its code`() {
        assertEquals("Before", htmlToPlainText("Before<script>alert(1); more text"))
        assertEquals("Before", htmlToPlainText("Before<STYLE type=\"text/css\">p { }"))
    }

    @Test
    fun `script and style match case-insensitively and leave similar tags alone`() {
        assertEquals("AB", htmlToPlainText("A<SCRIPT>x</ScRiPt >B"))
        assertEquals("a b", htmlToPlainText("<scripted>a</scripted> <styles>b</styles>"))
    }

    @Test(timeout = 30_000)
    fun `many unterminated openers are handled in linear time`() {
        listOf("<script", "<style", "<img", "<a", "<p", "<li", "<!").forEach { opener ->
            htmlToPlainText(opener.repeat(200_000))
            htmlToAnnotatedString(opener.repeat(200_000))
        }
    }

    @Test
    fun `huge input is cut off rather than parsed whole`() {
        val text = htmlToPlainText("x".repeat(1_000_000))
        assertTrue(text.length in 1..200_000)
    }
}
