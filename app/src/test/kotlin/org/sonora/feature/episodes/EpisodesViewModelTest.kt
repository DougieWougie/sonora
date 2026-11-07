// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.episodes

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.sonora.testutil.FakeSettingsStore

class EpisodesViewModelTest {
    private val settings = FakeSettingsStore()

    private fun vm() = EpisodesViewModel(settings)

    @Test
    fun `it opens on the queue when nothing was chosen yet`() {
        assertEquals(EpisodeSegment.QUEUE, vm().lastSegment)
    }

    @Test
    fun `it opens on the segment that was chosen last`() {
        settings.episodesSegment = "NEW"

        assertEquals(EpisodeSegment.NEW, vm().lastSegment)
    }

    @Test
    fun `a segment it does not know falls back to the queue`() {
        settings.episodesSegment = "ARCHIVED"

        assertEquals(EpisodeSegment.QUEUE, vm().lastSegment)
    }

    @Test
    fun `choosing a segment is remembered for next time`() {
        vm().remember(EpisodeSegment.DOWNLOADED)

        assertEquals("DOWNLOADED", settings.episodesSegment)
        assertEquals(EpisodeSegment.DOWNLOADED, vm().lastSegment)
    }
}
