// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.sonora.feature.player.PlayerEpisode
import org.sonora.feature.player.PlayerUiState
import org.sonora.ui.theme.ColorRoles
import org.sonora.ui.theme.ThemeMode
import org.sonora.ui.theme.ThemePreferences
import org.sonora.ui.theme.ThemeState
import org.sonora.ui.theme.toColorScheme

@RunWith(AndroidJUnit4::class)
class SonoraRootTest {
    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val seeds = mutableListOf<Int?>()
    private val episode = PlayerEpisode(id = 1, title = "The Episode", podcastTitle = "The Podcast", artworkUrl = null)
    private var primary: Color? = null

    private val screens = AppScreens(
        library = { _, _ -> Text("Library screen"); primary = MaterialTheme.colorScheme.primary },
        episodes = { _, _ -> },
        discover = { },
        settings = { },
        downloads = { _, _ -> },
        podcast = { _, _ -> },
    )

    private fun setContent(theme: ThemeState = ThemeState(), player: PlayerUiState = PlayerUiState()) {
        composeTestRule.setContent {
            SonoraRootContent(theme, player, emptyFlow(), {}, { seeds += it }, screens)
        }
    }

    @Test
    fun `it shows the library inside the app shell`() {
        setContent()

        composeTestRule.onNodeWithText("Library screen").assertIsDisplayed()
        composeTestRule.onNodeWithText("Discover").assertIsDisplayed()
    }

    @Test
    fun `it themes the screens from the theme state`() {
        val seed = 0xFFD2452B.toInt()
        setContent(ThemeState(ThemePreferences(ThemeMode.LIGHT), artworkSeed = seed))
        composeTestRule.mainClock.advanceTimeBy(5_000)
        composeTestRule.waitForIdle()

        assertEquals(ColorRoles.fromSeed(seed, dark = false).toColorScheme(dark = false).primary, primary)
    }

    @Test
    fun `with nothing playing the artwork colour is cleared`() {
        setContent()
        composeTestRule.waitForIdle()

        assertEquals(listOf<Int?>(null), seeds)
    }

    @Test
    fun `an episode without artwork leaves the current colour alone`() {
        setContent(player = PlayerUiState(episode = episode, durationMs = 1_000))
        composeTestRule.waitForIdle()

        assertEquals(emptyList<Int?>(), seeds)
    }
}
