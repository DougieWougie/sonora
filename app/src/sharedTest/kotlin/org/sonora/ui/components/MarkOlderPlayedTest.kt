// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialExpressiveTheme
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

@RunWith(AndroidJUnit4::class)
class MarkOlderPlayedTest {
    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val item = EpisodeItem(
        id = 7, podcastId = 1, podcastTitle = "Show", title = "Episode seven", publishedAtMillis = 700,
        durationSeconds = 600, positionSeconds = 0, played = false, downloadState = DownloadState.NONE,
        downloadedBytes = 0, sizeBytes = null, artworkUrl = null, queued = false,
    )

    @Test
    fun `the episode menu offers to mark older episodes as played`() {
        val requested = mutableListOf<EpisodeItem>()
        composeTestRule.setContent {
            MaterialExpressiveTheme {
                EpisodeRow(
                    item,
                    EpisodeActionsUi({}, {}, {}, {}, { _, _ -> }, onMarkOlderPlayed = { requested += it }),
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("More actions").performClick()
        composeTestRule.onNodeWithText("Mark older episodes as played").performClick()

        assertEquals(listOf(item), requested)
    }

    private fun setDialog(count: Int, onConfirm: () -> Unit = {}, onDismiss: () -> Unit = {}) {
        composeTestRule.setContent {
            MaterialExpressiveTheme { MarkOlderPlayedDialog(PendingMarkOlder(7, count), onConfirm, onDismiss) }
        }
    }

    @Test
    fun `the dialog states how many episodes will be marked`() {
        setDialog(37)
        composeTestRule.onNodeWithText("Mark 37 older episodes as played?").assertIsDisplayed()
    }

    @Test
    fun `the dialog uses the singular for one episode`() {
        setDialog(1)
        composeTestRule.onNodeWithText("Mark 1 older episode as played?").assertIsDisplayed()
    }

    @Test
    fun `confirming and cancelling call back`() {
        var confirmed = 0
        var dismissed = 0
        setDialog(3, onConfirm = { confirmed++ }, onDismiss = { dismissed++ })

        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.onNodeWithText("Mark as played").performClick()

        assertEquals(1, dismissed)
        assertEquals(1, confirmed)
    }

    @Test
    fun `with nothing older the dialog says so and offers no confirmation`() {
        setDialog(0)

        composeTestRule.onNodeWithText("No older unplayed episodes").assertIsDisplayed()
        composeTestRule.onNodeWithText("Mark as played").assertDoesNotExist()
    }
}
