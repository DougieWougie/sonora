// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
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
        subscriptions = { open, _ ->
            Column(Modifier.fillMaxSize()) {
                Text("Open podcast", Modifier.clickable { open(7) })
                Text("Subscriptions screen", Modifier.fillMaxSize())
            }
            primary = MaterialTheme.colorScheme.primary
        },
        queue = { _, _ -> Text("Queue screen", Modifier.fillMaxSize()) },
        discover = {
            val drag = rememberDraggableState { }
            Text("Discover screen", Modifier.fillMaxSize().draggable(drag, Orientation.Horizontal))
        },
        settings = { Text("Settings screen", Modifier.fillMaxSize()) },
        downloads = { _, _ -> },
        podcast = { _, _ -> Text("Podcast screen", Modifier.fillMaxSize()) },
    )

    private fun setContent(theme: ThemeState = ThemeState(), player: PlayerUiState = PlayerUiState()) {
        composeTestRule.setContent {
            SonoraRootContent(theme, player, emptyFlow(), {}, { seeds += it }, screens)
        }
    }

    @Test
    fun `it shows the library inside the app shell`() {
        setContent()

        composeTestRule.onNodeWithText("Subscriptions screen").assertIsDisplayed()
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

    private fun swipeOn(text: String, left: Boolean) {
        composeTestRule.onNodeWithText(text).performTouchInput { if (left) swipeLeft() else swipeRight() }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `swiping left moves to the next tab and right moves back`() {
        setContent()

        swipeOn("Subscriptions screen", left = true)
        composeTestRule.onNodeWithText("Queue screen").assertIsDisplayed()

        swipeOn("Queue screen", left = false)
        composeTestRule.onNodeWithText("Subscriptions screen").assertIsDisplayed()
    }

    @Test
    fun `swiping right on the first tab stays put`() {
        setContent()

        swipeOn("Subscriptions screen", left = false)

        composeTestRule.onNodeWithText("Subscriptions screen").assertIsDisplayed()
    }

    @Test
    fun `swiping left on the last tab stays put`() {
        setContent()
        composeTestRule.onNodeWithText("Settings").performClick()
        composeTestRule.waitForIdle()

        swipeOn("Settings screen", left = true)

        composeTestRule.onNodeWithText("Settings screen").assertIsDisplayed()
    }

    @Test
    fun `a screen that handles horizontal drags keeps them`() {
        setContent()
        composeTestRule.onNodeWithText("Discover").performClick()
        composeTestRule.waitForIdle()

        swipeOn("Discover screen", left = true)

        composeTestRule.onNodeWithText("Discover screen").assertIsDisplayed()
        composeTestRule.onNodeWithText("Settings screen").assertDoesNotExist()
    }

    @Test
    fun `swiping does not leave a podcast`() {
        setContent()
        composeTestRule.onNodeWithText("Open podcast").performClick()
        composeTestRule.waitForIdle()

        swipeOn("Podcast screen", left = true)

        composeTestRule.onNodeWithText("Podcast screen").assertIsDisplayed()
    }
}
