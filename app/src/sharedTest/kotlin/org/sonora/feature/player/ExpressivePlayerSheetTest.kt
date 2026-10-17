// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.player

import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.sonora.core.testing.TestTags

@RunWith(AndroidJUnit4::class)
class ExpressivePlayerSheetTest {
    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val episode = PlayerEpisode(id = 1, title = "The Episode", podcastTitle = "The Podcast", artworkUrl = null)
    private val intents = mutableListOf<PlayerIntent>()
    private var state by mutableStateOf(PlayerUiState(episode = episode, durationMs = 1_800_000, positionMs = 330_000))

    /** The same shared-transition arrangement as the app shell: mini player and full player swap places. */
    private fun setContent(expanded: Boolean = false) {
        composeTestRule.setContent {
            MaterialExpressiveTheme {
                var isExpanded by remember { mutableStateOf(expanded) }
                SharedTransitionLayout {
                    Box {
                        AnimatedVisibility(visible = !isExpanded) {
                            MiniPlayer(state, this, { intents += it }, onExpand = { isExpanded = true })
                        }
                        AnimatedVisibility(visible = isExpanded) {
                            FullPlayer(state, this, { intents += it }, onCollapse = { isExpanded = false })
                        }
                    }
                }
            }
        }
    }

    /** The full player scrolls; bring a control into view so the test works at any screen size. */
    private fun tagged(tag: String) = composeTestRule.onNodeWithTag(tag).performScrollTo()

    @Test
    fun collapsedShowsOnlyTheMiniPlayerUntilItIsClicked() {
        setContent()

        composeTestRule.onNodeWithTag(TestTags.MINI_PLAYER_BAR).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TestTags.FULL_PLAYER_SHEET).assertDoesNotExist()
        composeTestRule.onNodeWithTag(TestTags.PLAYER_TOOLBAR).assertDoesNotExist()
        composeTestRule.onNodeWithTag(TestTags.SPEED_CONTROL_BUTTON).assertDoesNotExist()
        composeTestRule.onNodeWithTag(TestTags.SKIP_SILENCE_CHIP).assertDoesNotExist()

        composeTestRule.onNodeWithTag(TestTags.MINI_PLAYER_BAR).performClick()

        composeTestRule.onNodeWithTag(TestTags.FULL_PLAYER_SHEET).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TestTags.SEEK_SLIDER).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TestTags.PLAYER_TOOLBAR).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TestTags.MINI_PLAYER_BAR).assertDoesNotExist()
    }

    @Test
    fun playPauseButtonIsLabelledForItsActionAndEmitsToggle() {
        setContent(expanded = true)

        composeTestRule.onNodeWithTag(TestTags.PLAY_PAUSE_BUTTON).assertContentDescriptionEquals("Play")
        tagged(TestTags.PLAY_PAUSE_BUTTON).performClick()
        assertEquals(listOf<PlayerIntent>(PlayerIntent.TogglePlayPause), intents)
    }

    @Test
    fun playPauseButtonReadsPauseWhilePlayingAndKeepsMorphingSmoothly() {
        setContent(expanded = true)
        composeTestRule.mainClock.autoAdvance = false

        composeTestRule.runOnUiThread { state = state.copy(isPlaying = true) }
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeBy(50)
        composeTestRule.onNodeWithTag(TestTags.PLAY_PAUSE_BUTTON).assertContentDescriptionEquals("Pause")

        // Step through the morph; the button stays put and tappable on every frame.
        repeat(10) {
            composeTestRule.mainClock.advanceTimeBy(50)
            tagged(TestTags.PLAY_PAUSE_BUTTON).assertIsDisplayed()
        }
        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.onNodeWithTag(TestTags.PLAY_PAUSE_BUTTON).assertContentDescriptionEquals("Pause")
    }

    @Test
    fun skipButtonsEmitTheirIntents() {
        setContent(expanded = true)

        tagged(TestTags.SKIP_BACK_BUTTON).performClick()
        tagged(TestTags.SKIP_FORWARD_BUTTON).performClick()

        assertEquals(listOf(PlayerIntent.Rewind, PlayerIntent.SkipForward), intents)
    }

    @Test
    fun skipSilenceChipReflectsStateAndEmitsToggle() {
        setContent(expanded = true)

        composeTestRule.onNodeWithTag(TestTags.SKIP_SILENCE_CHIP).assertIsNotSelected()
        tagged(TestTags.SKIP_SILENCE_CHIP).performClick()
        assertEquals(listOf<PlayerIntent>(PlayerIntent.ToggleSkipSilence), intents)

        state = state.copy(skipSilence = true)
        composeTestRule.onNodeWithTag(TestTags.SKIP_SILENCE_CHIP).assertIsSelected()
    }

    @Test
    fun speedButtonShowsTheSpeedAndOffersPresets() {
        setContent(expanded = true)

        composeTestRule.onNodeWithTag(TestTags.SPEED_CONTROL_BUTTON).assertTextEquals("1.0×")
        tagged(TestTags.SPEED_CONTROL_BUTTON).performClick()
        composeTestRule.onNodeWithTag(TestTags.speedPreset(1.25f)).performClick()

        assertEquals(listOf<PlayerIntent>(PlayerIntent.SetSpeed(1.25f)), intents)

        state = state.copy(speed = 1.5f)
        composeTestRule.onNodeWithTag(TestTags.SPEED_CONTROL_BUTTON).assertTextEquals("1.5×")
    }

    @Test
    fun sleepTimerButtonIsReachable() {
        setContent(expanded = true)

        tagged(TestTags.SLEEP_TIMER_BUTTON).assertIsDisplayed()
    }

    @Test
    fun scrubberShowsElapsedAndRemainingAndSeeksThroughSemantics() {
        setContent(expanded = true)

        composeTestRule.onNodeWithTag(TestTags.ELAPSED_TIME).assertTextEquals("5:30")
        composeTestRule.onNodeWithTag(TestTags.REMAINING_TIME).assertTextEquals("-24:30")

        composeTestRule.onNodeWithTag(TestTags.SEEK_SLIDER).performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }

        assertEquals(listOf<PlayerIntent>(PlayerIntent.SeekTo(900_000)), intents)
    }
}
