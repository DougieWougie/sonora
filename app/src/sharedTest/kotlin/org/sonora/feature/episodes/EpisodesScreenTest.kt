// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.episodes

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EpisodesScreenTest {
    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private var segment by mutableStateOf(EpisodeSegment.QUEUE)
    private val changes = mutableListOf<EpisodeSegment>()

    private fun setContent() {
        composeTestRule.setContent {
            MaterialExpressiveTheme {
                EpisodesContent(
                    segment = segment,
                    onSegmentChange = { changes += it; segment = it },
                    queue = { Text("queue list") },
                    new = { Text("new list") },
                    downloaded = { Text("downloaded list") },
                )
            }
        }
    }

    @Test
    fun `the segments are Queue, New and Downloaded`() {
        setContent()

        listOf("Queue", "New", "Downloaded").forEach { composeTestRule.onNodeWithText(it).assertIsDisplayed() }
    }

    @Test
    fun `only the chosen segment is shown`() {
        setContent()

        composeTestRule.onNodeWithText("Queue").assertIsSelected()
        composeTestRule.onNodeWithText("queue list").assertIsDisplayed()
        composeTestRule.onNodeWithText("new list").assertDoesNotExist()
        composeTestRule.onNodeWithText("downloaded list").assertDoesNotExist()
    }

    @Test
    fun `choosing a segment switches to it`() {
        setContent()

        composeTestRule.onNodeWithText("New").performClick()
        composeTestRule.onNodeWithText("new list").assertIsDisplayed()
        composeTestRule.onNodeWithText("queue list").assertDoesNotExist()

        composeTestRule.onNodeWithText("Downloaded").performClick()
        composeTestRule.onNodeWithText("downloaded list").assertIsDisplayed()

        assertEquals(listOf(EpisodeSegment.NEW, EpisodeSegment.DOWNLOADED), changes)
    }

    @Test
    fun `it can open on a given segment`() {
        segment = EpisodeSegment.DOWNLOADED
        setContent()

        composeTestRule.onNodeWithText("Downloaded").assertIsSelected()
        composeTestRule.onNodeWithText("downloaded list").assertIsDisplayed()
    }
}
