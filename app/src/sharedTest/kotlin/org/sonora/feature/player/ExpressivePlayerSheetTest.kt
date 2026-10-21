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
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertAny
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.sonora.core.testing.TestTags

@RunWith(AndroidJUnit4::class)
class ExpressivePlayerSheetTest {
    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val episode = PlayerEpisode(id = 1, title = "The Episode", podcastTitle = "The Podcast", artworkUrl = null)
    private val intents = mutableListOf<PlayerIntent>()
    private var shares = 0
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
                            FullPlayer(state, this, { intents += it }, onCollapse = { isExpanded = false }, onShare = { shares++ })
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
    fun miniPlayerIsRoomyWithALargePlayButton() {
        setContent()

        composeTestRule.onNodeWithTag(TestTags.MINI_PLAYER_BAR).assertHeightIsAtLeast(88.dp)
        composeTestRule.onNodeWithTag(TestTags.PLAY_PAUSE_BUTTON).assertHeightIsAtLeast(56.dp).assertWidthIsAtLeast(56.dp)
    }

    @Test
    fun playPauseButtonIsLabelledForItsActionAndEmitsToggle() {
        setContent(expanded = true)

        composeTestRule.onNodeWithTag(TestTags.PLAY_PAUSE_BUTTON).assertContentDescriptionEquals("Play")
        composeTestRule.onNodeWithTag(TestTags.PLAY_PAUSE_BUTTON).performClick()
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
            composeTestRule.onNodeWithTag(TestTags.PLAY_PAUSE_BUTTON).assertIsDisplayed()
        }
        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.onNodeWithTag(TestTags.PLAY_PAUSE_BUTTON).assertContentDescriptionEquals("Pause")
    }

    @Test
    fun skipButtonsEmitTheirIntents() {
        setContent(expanded = true)

        composeTestRule.onNodeWithTag(TestTags.SKIP_BACK_BUTTON).performClick()
        composeTestRule.onNodeWithTag(TestTags.SKIP_FORWARD_BUTTON).performClick()

        assertEquals(listOf(PlayerIntent.Rewind, PlayerIntent.SkipForward), intents)
    }

    @Test
    fun previousAndNextButtonsEmitTheirIntents() {
        state = state.copy(upNext = listOf(episode))
        setContent(expanded = true)

        composeTestRule.onNodeWithTag(TestTags.PREVIOUS_BUTTON).assertContentDescriptionEquals("Previous episode")
        composeTestRule.onNodeWithTag(TestTags.NEXT_BUTTON).assertContentDescriptionEquals("Next episode")
        composeTestRule.onNodeWithTag(TestTags.PREVIOUS_BUTTON).performClick()
        composeTestRule.onNodeWithTag(TestTags.NEXT_BUTTON).performClick()

        assertEquals(listOf(PlayerIntent.Previous, PlayerIntent.Next), intents)
    }

    @Test
    fun nextButtonIsDisabledWhenNothingIsQueued() {
        setContent(expanded = true)

        composeTestRule.onNodeWithTag(TestTags.NEXT_BUTTON).assertIsNotEnabled()
        composeTestRule.onNodeWithTag(TestTags.PREVIOUS_BUTTON).assertIsEnabled()
    }

    @Test
    fun skipSilenceChipReflectsStateAndEmitsToggle() {
        setContent(expanded = true)

        composeTestRule.onNodeWithTag(TestTags.SKIP_SILENCE_CHIP).assertIsNotSelected()
        tagged(TestTags.SKIP_SILENCE_CHIP).performClick()
        assertEquals(listOf<PlayerIntent>(PlayerIntent.ToggleSkipSilence), intents)

        state = state.copy(skipSilence = true)
        composeTestRule.onNodeWithTag(TestTags.SKIP_SILENCE_CHIP).assertIsSelected().assert(hasText("No gap"))
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

    private val longNotes = "<p>" + "Line of show notes. ".repeat(400) + "</p>"

    @Test
    fun topBarHasCollapseTitleAndShare() {
        setContent(expanded = true)

        composeTestRule.onNodeWithTag(TestTags.PLAYER_COLLAPSE_BUTTON).assertIsDisplayed()
        composeTestRule.onNodeWithText("Now playing").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Share episode").assertIsDisplayed()
        composeTestRule.onNodeWithTag(TestTags.PLAYER_SHARE_BUTTON).performClick()
        assertEquals(1, shares)
        composeTestRule.onNodeWithTag(TestTags.PLAYER_COLLAPSE_BUTTON).performClick()
        composeTestRule.onNodeWithTag(TestTags.FULL_PLAYER_SHEET).assertDoesNotExist()
    }

    @Test
    fun bodyScrollsVerticallyAndHoldsTheEpisodeDetails() {
        state = state.copy(episode = episode.copy(showNotes = longNotes))
        setContent(expanded = true)

        composeTestRule.onNodeWithTag(TestTags.PLAYER_SCROLL_BODY).assert(hasScrollAction())
        composeTestRule.onNodeWithTag(TestTags.PLAYER_SCROLL_BODY).onChildren().assertAny(hasText("The Episode"))
        composeTestRule.onNodeWithText("The Podcast").assertExists()
        composeTestRule.onNodeWithText("Show notes").assertExists()
        composeTestRule.onNodeWithTag(TestTags.PLAYER_TOOLBAR).assertExists()
        composeTestRule.onNodeWithTag(TestTags.PLAYER_SCROLL_BODY).performScrollToNode(hasTestTag(TestTags.SHOW_NOTES))
    }

    @Test
    fun transportDockStaysPinnedWhileShowNotesScroll() {
        state = state.copy(episode = episode.copy(showNotes = longNotes))
        setContent(expanded = true)
        val before = composeTestRule.onNodeWithTag(TestTags.PLAYER_DOCK).fetchSemanticsNode().boundsInRoot

        composeTestRule.onNodeWithTag(TestTags.PLAYER_SCROLL_BODY).performTouchInput { swipeUp() }
        composeTestRule.onNodeWithTag(TestTags.PLAYER_SCROLL_BODY).performScrollToNode(hasTestTag(TestTags.SHOW_NOTES))

        val dock = composeTestRule.onNodeWithTag(TestTags.PLAYER_DOCK)
        dock.assertIsDisplayed()
        assertEquals(before, dock.fetchSemanticsNode().boundsInRoot)
        for (tag in listOf(TestTags.SEEK_SLIDER, TestTags.ELAPSED_TIME, TestTags.REMAINING_TIME, TestTags.SKIP_BACK_BUTTON, TestTags.PLAY_PAUSE_BUTTON, TestTags.SKIP_FORWARD_BUTTON)) {
            composeTestRule.onNodeWithTag(tag).assertIsDisplayed()
        }
        // The scrolling body never contains the dock controls.
        composeTestRule.onNode(hasTestTag(TestTags.PLAY_PAUSE_BUTTON) and hasAnyAncestor(hasTestTag(TestTags.PLAYER_SCROLL_BODY))).assertDoesNotExist()
        assertTrue(dock.fetchSemanticsNode().boundsInRoot.bottom > before.top)
    }

    @Test
    fun skipButtonsNameTheirIntervals() {
        setContent(expanded = true)

        composeTestRule.onNodeWithTag(TestTags.SKIP_BACK_BUTTON).assertContentDescriptionEquals("Back 10 seconds")
        composeTestRule.onNodeWithTag(TestTags.SKIP_FORWARD_BUTTON).assertContentDescriptionEquals("Forward 30 seconds")
    }

    @Test
    fun utilityControlsAreLabelledChips() {
        setContent(expanded = true)

        tagged(TestTags.SPEED_CONTROL_BUTTON).assert(hasText("1.0×"))
        tagged(TestTags.SKIP_SILENCE_CHIP).assert(hasText("Gap"))
        tagged(TestTags.SLEEP_TIMER_BUTTON).assert(hasText("Sleep"))
        tagged(TestTags.QUEUE_CHIP).assert(hasText("Queue"))
    }

    @Test
    fun sleepChipShowsTheCountdownWhenActive() {
        state = state.copy(sleepTimerRemainingMs = 125_000)
        setContent(expanded = true)

        tagged(TestTags.SLEEP_TIMER_BUTTON).assert(hasText("2:05")).assertIsSelected()
    }

    @Test
    fun queueChipOpensTheUpNextSheet() {
        setContent(expanded = true)

        tagged(TestTags.QUEUE_CHIP).performClick()

        composeTestRule.onNodeWithText("Up next").assertIsDisplayed()
    }

    @Test
    fun scrubberShowsElapsedAndRemainingAndSeeksThroughSemantics() {
        setContent(expanded = true)

        composeTestRule.onNodeWithTag(TestTags.ELAPSED_TIME).assertTextEquals("5:30")
        composeTestRule.onNodeWithTag(TestTags.REMAINING_TIME).assertTextEquals("-24:30")

        composeTestRule.onNodeWithTag(TestTags.SEEK_SLIDER).performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }

        assertEquals(listOf<PlayerIntent>(PlayerIntent.SeekTo(900_000)), intents)
    }

    @Test
    fun showNotesAreShownAsTextWithoutTheMarkup() {
        state = state.copy(episode = episode.copy(showNotes = "<p>Hello <b>bold</b> <a href=\"https://example.org\">link</a></p><script>boom()</script>"))
        setContent(expanded = true)

        tagged(TestTags.SHOW_NOTES).assertTextEquals("Hello bold link")
    }

    @Test
    fun showNotesFollowTheCurrentEpisode() {
        state = state.copy(episode = episode.copy(showNotes = "First notes"))
        setContent(expanded = true)
        tagged(TestTags.SHOW_NOTES).assertTextEquals("First notes")

        state = state.copy(episode = episode.copy(id = 2, showNotes = "<p>Second notes</p>"))
        tagged(TestTags.SHOW_NOTES).assertTextEquals("Second notes")
    }

    @Test
    fun noShowNotesSectionWhenTheEpisodeHasNone() {
        setContent(expanded = true)
        composeTestRule.onNodeWithTag(TestTags.FULL_PLAYER_SHEET).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TestTags.SHOW_NOTES).assertDoesNotExist()

        state = state.copy(episode = episode.copy(showNotes = "<p> </p>"))
        composeTestRule.onNodeWithTag(TestTags.SHOW_NOTES).assertDoesNotExist()
    }
}
