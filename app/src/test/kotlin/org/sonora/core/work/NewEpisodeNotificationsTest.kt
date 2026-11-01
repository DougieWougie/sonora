// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.work

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.sonora.core.sync.NewEpisodes

class NewEpisodeNotificationsTest {
    private fun one(description: String? = null) =
        NewEpisodes(1, "Space Talk", listOf("Mars landing"), latestDescription = description)

    private fun many(count: Int) =
        NewEpisodes(1, "Space Talk", (count downTo 1).map { "Ep $it" }, latestDescription = "<p>Ignored</p>")

    // --- one new episode ---------------------------------------------------------------------

    @Test
    fun `one episode is titled by the episode with the show as sub text`() {
        val content = NewEpisodeNotifications.contentFor(one("<p>All about <b>space</b> &amp; more</p>"))

        assertEquals("Mars landing", content.title)
        assertEquals("Space Talk", content.subText)
        assertEquals("All about space & more", content.text)
        assertEquals("All about space & more", content.expandedText)
        assertTrue(content.lines.isEmpty())
        assertEquals(0, content.moreCount)
    }

    @Test
    fun `a long description is cut at a word with an ellipsis, shorter when collapsed`() {
        val words = (1..200).joinToString(" ") { "word$it" }

        val content = NewEpisodeNotifications.contentFor(one("<p>$words</p>"))

        assertTrue(content.text.length <= NewEpisodeNotifications.COLLAPSED_CHARS + 1, content.text.length.toString())
        assertTrue(content.text.endsWith("…"))
        assertTrue(content.expandedText!!.length <= NewEpisodeNotifications.EXPANDED_CHARS + 1)
        assertTrue(content.expandedText.length > content.text.length)
        assertTrue(content.expandedText.endsWith("…"))
        // Cut between words, never in the middle of one.
        assertTrue(Regex("word\\d+…$").containsMatchIn(content.text), content.text)
    }

    @Test
    fun `the expanded text is the first two sentences, the collapsed text one line of them`() {
        val content = NewEpisodeNotifications.contentFor(
            one("<p>We land on Mars. Then things go wrong! Nobody expected the third part.</p>"),
        )

        assertEquals("We land on Mars. Then things go wrong!", content.expandedText)
        assertEquals("We land on Mars. Then things go wrong!", content.text)
    }

    @Test
    fun `questions and closing quotes end a sentence too`() {
        val content = NewEpisodeNotifications.contentFor(
            one("Is it \"safe\"? Maybe (we think). Not at all. Really."),
        )

        assertEquals("Is it \"safe\"? Maybe (we think).", content.expandedText)
    }

    @Test
    fun `abbreviations and decimals do not end a sentence`() {
        val content = NewEpisodeNotifications.contentFor(
            one("Dr. smith visits v1.5 of the lab. It is big. Extra."),
        )

        assertEquals("Dr. smith visits v1.5 of the lab. It is big.", content.expandedText)
    }

    @Test
    fun `fewer than two sentences are shown whole`() {
        assertEquals("Just one thing", NewEpisodeNotifications.contentFor(one("<p>Just one thing</p>")).expandedText)
        assertEquals("One.", NewEpisodeNotifications.contentFor(one("<p>One.</p> ")).expandedText)
    }

    @Test
    fun `two very long sentences still respect the character cap`() {
        val sentence = (1..120).joinToString(" ") { "w$it" } + "."

        val content = NewEpisodeNotifications.contentFor(one("$sentence $sentence"))

        assertTrue(content.expandedText!!.length <= NewEpisodeNotifications.EXPANDED_CHARS + 1)
        assertTrue(content.expandedText.endsWith("…"))
    }

    @Test
    fun `markup is stripped, scripts dropped and whitespace collapsed`() {
        val content = NewEpisodeNotifications.contentFor(one("<script>alert(1)</script><p>Line one</p>\n\n<p>  Line   two </p>"))

        assertEquals("Line one Line two", content.text)
    }

    @Test
    fun `without a description the show name fills the text and there is nothing to expand`() {
        listOf(null, "", "   ", "<p> </p>").forEach { blank ->
            val content = NewEpisodeNotifications.contentFor(one(blank))

            assertEquals("Space Talk", content.text, "description=$blank")
            assertNull(content.expandedText, "description=$blank")
        }
    }

    // --- several new episodes ----------------------------------------------------------------

    @Test
    fun `several episodes are titled by the show and list their titles newest first`() {
        val content = NewEpisodeNotifications.contentFor(many(3))

        assertEquals("Space Talk", content.title)
        assertEquals("3 new episodes · latest: Ep 3", content.text)
        assertNull(content.subText)
        assertEquals(listOf("Ep 3", "Ep 2", "Ep 1"), content.lines)
        assertEquals(0, content.moreCount)
        assertNull(content.expandedText)
    }

    @Test
    fun `at most five titles are listed and the rest are counted`() {
        val content = NewEpisodeNotifications.contentFor(many(8))

        assertEquals(listOf("Ep 8", "Ep 7", "Ep 6", "Ep 5", "Ep 4"), content.lines)
        assertEquals(3, content.moreCount)
        assertEquals("8 new episodes · latest: Ep 8", content.text)
    }

    @Test
    fun `exactly five titles need no more line`() {
        val content = NewEpisodeNotifications.contentFor(many(5))

        assertEquals(5, content.lines.size)
        assertEquals(0, content.moreCount)
    }

    // --- ids and links -----------------------------------------------------------------------

    @Test
    fun `each podcast gets its own notification id`() {
        assertEquals(NewEpisodeNotifications.idFor(7), NewEpisodeNotifications.idFor(7))
        assertNotEquals(NewEpisodeNotifications.idFor(7), NewEpisodeNotifications.idFor(8))
    }

    @Test
    fun `ids stay clear of the download notification ids`() {
        val ids = (0L..10_000L step 97).map(NewEpisodeNotifications::idFor)
        val downloads = (0L..4095L).map(DownloadNotifications::idFor).toSet()

        assertEquals(emptySet<Int>(), ids.toSet() intersect downloads)
    }

    @Test
    fun `tapping opens that podcast's page`() {
        assertEquals("sonora://podcast/42", NewEpisodeNotifications.showLink(42))
    }
}
