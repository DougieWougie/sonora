// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.ui

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.sonora.core.database.EpisodeEntity
import org.sonora.core.database.FakeEpisodeDao
import org.sonora.testutil.MainDispatcherExtension
import org.sonora.testutil.awaitUntil

class NavBadgesTest {
    @JvmField @RegisterExtension
    val main = MainDispatcherExtension()

    private val episodes = FakeEpisodeDao()

    private suspend fun add(guid: String, played: Boolean = false) {
        episodes.insertAll(
            listOf(
                EpisodeEntity(
                    podcastId = 1, guid = guid, title = guid, enclosureUrl = "https://e/$guid", played = played,
                ),
            ),
        )
    }

    @Test
    fun `no badge when the count is zero`() {
        assertNull(badgeLabel(0))
    }

    @Test
    fun `no badge when the count is negative`() {
        assertNull(badgeLabel(-1))
    }

    @Test
    fun `small counts are shown as they are`() {
        assertEquals("1", badgeLabel(1))
        assertEquals("99", badgeLabel(99))
    }

    @Test
    fun `large counts are capped`() {
        assertEquals("99+", badgeLabel(100))
        assertEquals("99+", badgeLabel(12_345))
    }

    @Test
    fun `the episodes badge counts unplayed episodes`() = runTest {
        add("a")
        add("b")
        add("c", played = true)

        NavBadgesViewModel(episodes).badges.test {
            assertEquals(NavBadges(episodes = 2), awaitUntil { it.episodes == 2 })
        }
    }

    @Test
    fun `the episodes badge follows episodes being played`() = runTest {
        add("a")
        add("b")

        NavBadgesViewModel(episodes).badges.test {
            awaitUntil { it.episodes == 2 }
            episodes.rows.value = episodes.rows.value.map { if (it.guid == "a") it.copy(played = true) else it }
            assertEquals(1, awaitUntil { it.episodes == 1 }.episodes)
        }
    }
}
