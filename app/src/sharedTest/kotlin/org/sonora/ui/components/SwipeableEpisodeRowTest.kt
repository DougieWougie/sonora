// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.sonora.core.model.DownloadState
import org.sonora.feature.common.EpisodeItem

@RunWith(AndroidJUnit4::class)
class SwipeableEpisodeRowTest {
    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val item = EpisodeItem(
        id = 7, podcastId = 1, podcastTitle = "Show", title = "Episode seven", publishedAtMillis = 700,
        durationSeconds = 600, positionSeconds = 0, played = false, downloadState = DownloadState.NONE,
        downloadedBytes = 0, sizeBytes = null, artworkUrl = null, queued = false,
    )
    private val queued = mutableListOf<EpisodeItem>()
    private val downloaded = mutableListOf<EpisodeItem>()

    private fun setContent() {
        composeTestRule.setContent {
            MaterialExpressiveTheme {
                SwipeableEpisodeRow(
                    item,
                    EpisodeActionsUi({}, { queued += it }, { downloaded += it }, {}, { _, _ -> }, {}),
                )
            }
        }
    }

    private fun titleLeft() = composeTestRule.onNodeWithText("Episode seven").getUnclippedBoundsInRoot().left

    @Test
    fun `swiping right queues the episode and the row snaps back`() {
        setContent()
        val restingLeft = titleLeft()

        composeTestRule.onNodeWithText("Episode seven").performTouchInput { swipeRight() }
        composeTestRule.waitForIdle()

        assertEquals(listOf(item), queued)
        assertEquals(restingLeft, titleLeft())
    }

    @Test
    fun `swiping left downloads the episode and the row snaps back`() {
        setContent()
        val restingLeft = titleLeft()

        composeTestRule.onNodeWithText("Episode seven").performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()

        assertEquals(listOf(item), downloaded)
        assertEquals(restingLeft, titleLeft())
    }

    @Test
    fun `the same swipe can be repeated`() {
        setContent()

        repeat(2) {
            composeTestRule.onNodeWithText("Episode seven").performTouchInput { swipeRight() }
            composeTestRule.waitForIdle()
        }

        assertEquals(listOf(item, item), queued)
    }
}
