// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.podcast

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.sonora.core.model.DownloadState
import org.sonora.feature.common.EpisodeItem
import org.sonora.feature.common.PendingMarkOlder
import org.sonora.ui.components.EpisodeActionsUi

@RunWith(AndroidJUnit4::class)
class PodcastDetailScreenTest {
    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val intents = mutableListOf<PodcastDetailIntent>()
    private val played = mutableListOf<EpisodeItem>()
    private var backs = 0
    private var state by mutableStateOf(PodcastDetailUiState(podcast = header(), isLoading = false))

    private companion object {
        fun header(autoDownload: Boolean = false) = PodcastHeader(
            id = 1, feedUrl = "https://f/feed", title = "Space Talk", author = "Neil",
            description = "<p>All about <b>space</b></p>", imageUrl = null, autoDownload = autoDownload,
        )
    }

    private fun episode(id: Long, title: String) = EpisodeItem(
        id = id, podcastId = 1, podcastTitle = null, title = title, publishedAtMillis = null,
        durationSeconds = null, positionSeconds = 0, played = false, downloadState = DownloadState.NONE,
        downloadedBytes = 0, sizeBytes = null, artworkUrl = null, queued = false,
    )

    private fun setContent() {
        composeTestRule.setContent {
            MaterialExpressiveTheme {
                PodcastDetailContent(
                    state,
                    EpisodeActionsUi({ played += it }, {}, {}, {}, { _, _ -> }, {}),
                    onBack = { backs++ },
                    onIntent = { intents += it },
                )
            }
        }
    }

    // --- header --------------------------------------------------------------------------------

    @Test
    fun `the header shows the podcast and its description as plain text`() {
        setContent()

        composeTestRule.onNodeWithText("Space Talk").assertIsDisplayed()
        composeTestRule.onNodeWithText("Neil").assertIsDisplayed()
        composeTestRule.onNodeWithText("All about space").assertIsDisplayed()
    }

    @Test
    fun `back leaves the screen`() {
        setContent()

        composeTestRule.onNodeWithContentDescription("Back").performClick()

        assertEquals(1, backs)
    }

    @Test
    fun `the subscribe button toggles the subscription`() {
        state = state.copy(subscribed = false)
        setContent()

        composeTestRule.onNodeWithText("Subscribe").performClick()

        assertEquals(listOf<PodcastDetailIntent>(PodcastDetailIntent.ToggleSubscription), intents)
    }

    @Test
    fun `options are unavailable until subscribed`() {
        state = state.copy(subscribed = false)
        setContent()

        composeTestRule.onNodeWithContentDescription("Options").assertIsNotEnabled()
    }

    @Test
    fun `the options menu toggles auto-download and refreshes`() {
        setContent()

        composeTestRule.onNodeWithText("Subscribed").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Options").assertIsEnabled().performClick()
        composeTestRule.onNodeWithText("Auto-download new episodes").performClick()
        composeTestRule.onNodeWithContentDescription("Options").performClick()
        composeTestRule.onNodeWithText("Refresh now").performClick()

        assertEquals(listOf(PodcastDetailIntent.ToggleAutoDownload, PodcastDetailIntent.Refresh), intents)
    }

    @Test
    fun `the options menu offers to stop auto-downloading when it is on`() {
        state = state.copy(podcast = header(autoDownload = true))
        setContent()

        composeTestRule.onNodeWithContentDescription("Options").performClick()

        composeTestRule.onNodeWithText("Stop auto-downloading new episodes").assertIsDisplayed()
    }

    // --- search and filters --------------------------------------------------------------------

    @Test
    fun `typing searches the episodes`() {
        setContent()

        composeTestRule.onNode(hasSetTextAction()).performTextInput("mars")

        assertEquals(listOf<PodcastDetailIntent>(PodcastDetailIntent.SetQuery("mars")), intents)
    }

    @Test
    fun `the filter chips mark the current filter and choose another`() {
        state = state.copy(filter = EpisodeFilter.UNPLAYED)
        setContent()

        composeTestRule.onNodeWithText("Unplayed").assertIsSelected()
        composeTestRule.onNodeWithText("All").performClick()
        composeTestRule.onNodeWithText("Downloaded").performClick()

        assertEquals(
            listOf(PodcastDetailIntent.SetFilter(EpisodeFilter.ALL), PodcastDetailIntent.SetFilter(EpisodeFilter.DOWNLOADED)),
            intents,
        )
    }

    // --- episodes ------------------------------------------------------------------------------

    @Test
    fun `episodes are listed and play on tap`() {
        val first = episode(1, "Intro to Space")
        state = state.copy(episodes = listOf(first, episode(2, "Mars landing")))
        setContent()

        composeTestRule.onNodeWithText("Mars landing").assertIsDisplayed()
        composeTestRule.onNodeWithText("Intro to Space").performClick()

        assertEquals(listOf(first), played)
    }

    @Test
    fun `an unsubscribed podcast with no episodes asks you to subscribe`() {
        state = state.copy(subscribed = false)
        setContent()

        composeTestRule.onNodeWithText("Subscribe to see episodes.").assertIsDisplayed()
    }

    @Test
    fun `a subscribed podcast with no matching episodes says so`() {
        setContent()

        composeTestRule.onNodeWithText("No episodes match.").assertIsDisplayed()
    }

    @Test
    fun `while loading neither empty message is shown`() {
        state = state.copy(isLoading = true)
        setContent()

        composeTestRule.onNodeWithText("No episodes match.").assertDoesNotExist()
        composeTestRule.onNodeWithText("Subscribe to see episodes.").assertDoesNotExist()
    }

    @Test
    fun `a pending mark-older request asks for confirmation`() {
        state = state.copy(pendingMarkOlder = PendingMarkOlder(episodeId = 2, count = 1))
        setContent()

        composeTestRule.onNodeWithText("Mark 1 older episode as played?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Mark as played").performClick()
        composeTestRule.onNodeWithText("Cancel").performClick()

        assertEquals(
            listOf(PodcastDetailIntent.ConfirmMarkOlderPlayed, PodcastDetailIntent.DismissMarkOlderPlayed),
            intents,
        )
    }
}
