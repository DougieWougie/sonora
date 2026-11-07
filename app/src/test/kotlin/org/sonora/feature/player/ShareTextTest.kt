// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ShareTextTest {
    private val episode = PlayerEpisode(1, "The Episode", "The Podcast", null, link = "https://example.org/ep")

    @Test
    fun `shares title and show`() {
        assertEquals("The Episode — The Podcast", shareText(episode.copy(link = null), includeLink = true))
    }

    @Test
    fun `appends the link on its own line when asked to`() {
        assertEquals("The Episode — The Podcast\nhttps://example.org/ep", shareText(episode, includeLink = true))
    }

    @Test
    fun `leaves the link out when the option is off`() {
        assertEquals("The Episode — The Podcast", shareText(episode, includeLink = false))
    }

    @Test
    fun `shares just the title when the show is unknown`() {
        assertEquals("The Episode", shareText(episode.copy(podcastTitle = null, link = null), includeLink = true))
    }
}
