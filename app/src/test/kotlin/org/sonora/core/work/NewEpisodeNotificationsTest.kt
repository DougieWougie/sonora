// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.work

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.sonora.core.sync.NewEpisodes

class NewEpisodeNotificationsTest {
    @Test
    fun `one new episode is titled by the show and names the episode`() {
        val text = NewEpisodeNotifications.textFor(NewEpisodes(1, "Space Talk", 1, "Mars landing"))

        assertEquals("Space Talk", text.title)
        assertEquals("Mars landing", text.body)
    }

    @Test
    fun `several new episodes are counted and the newest is named`() {
        val text = NewEpisodeNotifications.textFor(NewEpisodes(1, "Space Talk", 3, "Mars landing"))

        assertEquals("Space Talk", text.title)
        assertEquals("3 new episodes · latest: Mars landing", text.body)
    }

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
}
