// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.episodes

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
class EpisodesScreenTest {
    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val intents = mutableListOf<EpisodesIntent>()
    private val played = mutableListOf<EpisodeItem>()
    private var state by mutableStateOf(EpisodesUiState(isLoading = false))

    private fun item(id: Long, title: String) = EpisodeItem(
        id = id, podcastId = 1, podcastTitle = "Show", title = title, publishedAtMillis = null,
        durationSeconds = null, positionSeconds = 0, played = false, downloadState = DownloadState.NONE,
        downloadedBytes = 0, sizeBytes = null, artworkUrl = null, queued = false,
    )

    private fun setContent() {
        composeTestRule.setContent {
            MaterialExpressiveTheme {
                EpisodesContent(
                    state,
                    EpisodeActionsUi({ played += it }, {}, {}, {}, { _, _ -> }, {}),
                    { intents += it },
                    downloadsLink = { Text("Manage downloads") },
                )
            }
        }
    }

    @Test
    fun `while loading only a spinner is shown`() {
        state = EpisodesUiState(isLoading = true)
        setContent()

        composeTestRule.onNodeWithText("Episodes").assertDoesNotExist()
    }

    @Test
    fun `empty queue and inbox explain themselves`() {
        setContent()

        composeTestRule.onNodeWithText("Episodes").assertIsDisplayed()
        composeTestRule.onNodeWithText("Manage downloads").assertIsDisplayed()
        composeTestRule.onNodeWithText("Swipe an episode right to queue it.").assertIsDisplayed()
        composeTestRule.onNodeWithText("You're all caught up.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Clear").assertDoesNotExist()
    }

    @Test
    fun `queued and inbox episodes are listed under their headings`() {
        state = state.copy(queue = listOf(item(1, "Queued one")), inbox = listOf(item(2, "Inbox one")))
        setContent()

        composeTestRule.onNodeWithText("Up next").assertIsDisplayed()
        composeTestRule.onNodeWithText("Queued one").assertIsDisplayed()
        composeTestRule.onNodeWithText("Inbox").assertIsDisplayed()
        composeTestRule.onNodeWithText("Inbox one").assertIsDisplayed()
        composeTestRule.onNodeWithText("Swipe an episode right to queue it.").assertDoesNotExist()
        composeTestRule.onNodeWithText("You're all caught up.").assertDoesNotExist()
    }

    @Test
    fun `clearing the queue is offered only when there is one`() {
        state = state.copy(queue = listOf(item(1, "Queued one")))
        setContent()

        composeTestRule.onNodeWithText("Clear").performClick()

        assertEquals(listOf<EpisodesIntent>(EpisodesIntent.ClearQueue), intents)
    }

    @Test
    fun `a queue can be played or shuffled from its heading`() {
        state = state.copy(queue = listOf(item(1, "Queued one")))
        setContent()

        composeTestRule.onNodeWithContentDescription("Play queue").performClick()
        composeTestRule.onNodeWithContentDescription("Shuffle queue").performClick()

        assertEquals(listOf(EpisodesIntent.PlayQueue, EpisodesIntent.ShuffleQueue), intents)
    }

    @Test
    fun `play and shuffle are offered only when there is a queue`() {
        setContent()

        composeTestRule.onNodeWithContentDescription("Play queue").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Shuffle queue").assertDoesNotExist()
    }

    @Test
    fun `tapping an episode plays it`() {
        val inbox = item(2, "Inbox one")
        state = state.copy(inbox = listOf(inbox))
        setContent()

        composeTestRule.onNodeWithText("Inbox one").performClick()

        assertEquals(listOf(inbox), played)
    }

    @Test
    fun `a pending mark-older request asks for confirmation`() {
        state = state.copy(pendingMarkOlder = PendingMarkOlder(episodeId = 2, count = 5))
        setContent()

        composeTestRule.onNodeWithText("Mark 5 older episodes as played?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Mark as played").performClick()
        composeTestRule.onNodeWithText("Cancel").performClick()

        assertEquals(
            listOf(EpisodesIntent.ConfirmMarkOlderPlayed, EpisodesIntent.DismissMarkOlderPlayed),
            intents,
        )
    }
}
