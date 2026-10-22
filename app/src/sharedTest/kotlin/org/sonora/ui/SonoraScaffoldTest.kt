// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.ui

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.sonora.core.media.PlaybackFailure
import org.sonora.core.media.PlaybackFailureReason
import org.sonora.core.testing.TestTags
import org.sonora.feature.player.PlayerEpisode
import org.sonora.feature.player.PlayerEvent
import org.sonora.feature.player.PlayerIntent
import org.sonora.feature.player.PlayerUiState

@RunWith(AndroidJUnit4::class)
class SonoraScaffoldTest {
    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val episode = PlayerEpisode(id = 1, title = "The Episode", podcastTitle = "The Podcast", artworkUrl = null)
    private val intents = mutableListOf<PlayerIntent>()
    private val events = Channel<PlayerEvent>(Channel.BUFFERED)
    private var player by mutableStateOf(PlayerUiState())

    /** The real shell and routes, with plain text standing in for the Hilt-backed screens. */
    private fun setContent(layout: NavigationLayout = NavigationLayout.BottomBar, badges: NavBadges = NavBadges()) {
        composeTestRule.setContent {
            MaterialExpressiveTheme {
                SonoraScaffold(player, events.receiveAsFlow(), { intents += it }, layout, badges) { navController, _, modifier ->
                    NavHost(navController, startDestination = LibraryRoute, modifier = modifier) {
                        composable<LibraryRoute> { Text("Library screen") }
                        composable<EpisodesRoute> { Text("Episodes screen") }
                        composable<DiscoverRoute> { Text("Discover screen") }
                        composable<SettingsRoute> { Text("Settings screen") }
                    }
                }
            }
        }
    }

    private fun playing() {
        player = PlayerUiState(episode = episode, durationMs = 1_800_000)
    }

    // --- navigation ----------------------------------------------------------------------------

    @Test
    fun `the app opens on the library`() {
        setContent()

        composeTestRule.onNodeWithText("Library screen").assertIsDisplayed()
    }

    private fun navigateBetweenTopLevelScreens() {
        composeTestRule.onNodeWithText("Library").assertIsSelected()

        composeTestRule.onNodeWithText("Discover").performClick()
        composeTestRule.onNodeWithText("Discover screen").assertIsDisplayed()
        composeTestRule.onNodeWithText("Discover").assertIsSelected()

        composeTestRule.onNodeWithText("Settings").performClick()
        composeTestRule.onNodeWithText("Settings screen").assertIsDisplayed()

        composeTestRule.onNodeWithText("Episodes").performClick()
        composeTestRule.onNodeWithText("Episodes screen").assertIsDisplayed()

        composeTestRule.onNodeWithText("Library").performClick()
        composeTestRule.onNodeWithText("Library screen").assertIsDisplayed()
    }

    // --- badges --------------------------------------------------------------------------------

    private fun assertEpisodesBadgeShown(layout: NavigationLayout) {
        setContent(layout, NavBadges(episodes = 7))

        composeTestRule.onNodeWithTag(TestTags.NAV_BADGE_EPISODES, useUnmergedTree = true).assertIsDisplayed().assertContentDescriptionEquals("7 unplayed")
    }

    @Test
    fun `the episodes tab shows how many episodes are unplayed in the bottom bar`() =
        assertEpisodesBadgeShown(NavigationLayout.BottomBar)

    @Test
    fun `the episodes tab shows how many episodes are unplayed in the slim bottom bar`() =
        assertEpisodesBadgeShown(NavigationLayout.SlimBottomBar)

    @Test
    fun `the episodes tab shows how many episodes are unplayed in the rail`() =
        assertEpisodesBadgeShown(NavigationLayout.Rail)

    @Test
    fun `the episodes badge is capped for large counts`() {
        setContent(badges = NavBadges(episodes = 250))

        composeTestRule.onNodeWithTag(TestTags.NAV_BADGE_EPISODES, useUnmergedTree = true).assertContentDescriptionEquals("99+ unplayed")
    }

    @Test
    fun `there is no badge when nothing is unplayed`() {
        setContent(badges = NavBadges(episodes = 0))

        composeTestRule.onNodeWithTag(TestTags.NAV_BADGE_EPISODES, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `the bottom bar navigates between the top-level screens`() {
        setContent(NavigationLayout.BottomBar)

        navigateBetweenTopLevelScreens()
    }

    @Test
    fun `the slim bottom bar navigates between the top-level screens`() {
        setContent(NavigationLayout.SlimBottomBar)

        navigateBetweenTopLevelScreens()
    }

    @Test
    fun `the rail navigates between the top-level screens`() {
        setContent(NavigationLayout.Rail)

        navigateBetweenTopLevelScreens()
    }

    // --- mini and full player ------------------------------------------------------------------

    @Test
    fun `no player is shown until an episode is loaded`() {
        setContent()

        composeTestRule.onNodeWithTag(TestTags.MINI_PLAYER_BAR).assertDoesNotExist()
        composeTestRule.onNodeWithTag(TestTags.FULL_PLAYER_SHEET).assertDoesNotExist()
    }

    @Test
    fun `the mini player expands to the full player and collapses again`() {
        playing()
        setContent()

        composeTestRule.onNodeWithTag(TestTags.MINI_PLAYER_BAR).performClick()
        composeTestRule.onNodeWithTag(TestTags.FULL_PLAYER_SHEET).assertIsDisplayed()

        composeTestRule.onNodeWithTag(TestTags.PLAYER_COLLAPSE_BUTTON).performClick()
        composeTestRule.onNodeWithTag(TestTags.FULL_PLAYER_SHEET).assertDoesNotExist()
        composeTestRule.onNodeWithTag(TestTags.MINI_PLAYER_BAR).assertIsDisplayed()
    }

    @Test
    fun `back collapses the full player`() {
        playing()
        setContent()
        composeTestRule.onNodeWithTag(TestTags.MINI_PLAYER_BAR).performClick()
        composeTestRule.waitForIdle() // the BackHandler is only enabled once the expanded state has recomposed

        composeTestRule.runOnUiThread { composeTestRule.activity.onBackPressedDispatcher.onBackPressed() }

        composeTestRule.onNodeWithTag(TestTags.FULL_PLAYER_SHEET).assertDoesNotExist()
    }

    @Test
    fun `the full player closes when the episode goes away`() {
        playing()
        setContent()
        composeTestRule.onNodeWithTag(TestTags.MINI_PLAYER_BAR).performClick()

        player = PlayerUiState()

        composeTestRule.onNodeWithTag(TestTags.FULL_PLAYER_SHEET).assertDoesNotExist()
        composeTestRule.onNodeWithTag(TestTags.MINI_PLAYER_BAR).assertDoesNotExist()
    }

    @Test
    fun `sharing opens a chooser for the episode`() {
        playing()
        setContent()
        composeTestRule.onNodeWithTag(TestTags.MINI_PLAYER_BAR).performClick()

        composeTestRule.onNodeWithTag(TestTags.PLAYER_SHARE_BUTTON).performClick()

        val started = org.robolectric.Shadows.shadowOf(composeTestRule.activity).nextStartedActivity
        assertNotNull(started)
        assertEquals(Intent.ACTION_CHOOSER, started.action)
    }

    // --- playback failures ---------------------------------------------------------------------

    @Test
    fun `a failure that could work next time offers a retry`() {
        playing()
        setContent()

        events.trySend(PlayerEvent.PlaybackFailed(PlaybackFailure(PlaybackFailureReason.NETWORK)))
        composeTestRule.onNodeWithText("Couldn't reach the server. Check your connection and try again.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Retry").performClick()

        assertEquals(listOf<PlayerIntent>(PlayerIntent.TogglePlayPause), intents)
    }

    @Test
    fun `a failure that cannot work again offers no retry`() {
        playing()
        setContent()

        events.trySend(PlayerEvent.PlaybackFailed(PlaybackFailure(PlaybackFailureReason.UNSUPPORTED)))

        composeTestRule.onNodeWithText("Sonora can't play this episode's audio format.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test
    fun `a failure while the full player is open is shown above it`() {
        playing()
        setContent()
        composeTestRule.onNodeWithTag(TestTags.MINI_PLAYER_BAR).performClick()

        events.trySend(PlayerEvent.PlaybackFailed(PlaybackFailure(PlaybackFailureReason.OTHER)))

        composeTestRule.onNodeWithText("Couldn't play this episode.").assertIsDisplayed()
        composeTestRule.onNodeWithTag(TestTags.FULL_PLAYER_SHEET).assertIsDisplayed()
    }
}
