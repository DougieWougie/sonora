// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The search text is pure Kotlin so view-model tests can use it without Robolectric. */
class HtmlSearchTextTest {
    @Test
    fun `removes tags`() {
        assertEquals("Hello bold world", htmlToSearchText("<p>Hello <b>bold</b> world</p>"))
    }

    @Test
    fun `decodes the common entities`() {
        assertEquals("Tom & Jerry <3 \"q\" 'a' b", htmlToSearchText("Tom &amp; Jerry &lt;3 &quot;q&quot; &#39;a&#39; b"))
        assertEquals("a b", htmlToSearchText("a&nbsp;b"))
    }

    @Test
    fun `does not hide attribute values behind tags`() {
        assertEquals("the moon", htmlToSearchText("<a href=\"https://rockets.example\">the moon</a>"))
    }

    @Test
    fun `leaves plain text alone`() {
        assertEquals("space-saving tips", htmlToSearchText("space-saving tips"))
    }
}
