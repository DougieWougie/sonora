// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.library

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.sonora.ui.assertScreenHeading
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryScreenTest {
    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val intents = mutableListOf<SubscriptionsIntent>()
    private val opened = mutableListOf<Long>()
    private var state by mutableStateOf(SubscriptionsUiState(isLoading = false))

    private fun podcast(id: Long, title: String, author: String? = null, unplayed: Int = 0) = PodcastItem(
        id = id, feedUrl = "https://f/$id", title = title, author = author, imageUrl = null,
        unplayedCount = unplayed, autoDownload = false,
    )

    private fun setContent() {
        composeTestRule.setContent {
            MaterialExpressiveTheme { LibraryContent(state, { intents += it }, { opened += it }) }
        }
    }

    // --- content states ------------------------------------------------------------------------

    @Test
    fun `an empty library points to Discover`() {
        setContent()

        composeTestRule.assertScreenHeading("Library")
        composeTestRule.onNodeWithText("No subscriptions yet", substring = true).assertIsDisplayed()
    }

    @Test
    fun `an empty filtered library says nothing is new`() {
        state = state.copy(unplayedOnly = true)
        setContent()

        composeTestRule.onNodeWithText("Nothing new right now.").assertIsDisplayed()
        composeTestRule.onNodeWithText("No subscriptions yet", substring = true).assertDoesNotExist()
    }

    @Test
    fun `while loading neither list nor empty message is shown`() {
        state = SubscriptionsUiState(isLoading = true)
        setContent()

        composeTestRule.assertScreenHeading("Library")
        composeTestRule.onNodeWithText("No subscriptions yet", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the grid shows titles, authors and unplayed badges`() {
        state = state.copy(podcasts = listOf(podcast(1, "Alpha", "Ann", unplayed = 4), podcast(2, "Beta")))
        setContent()

        composeTestRule.onNodeWithText("Alpha").assertIsDisplayed()
        composeTestRule.onNodeWithText("Ann").assertIsDisplayed()
        composeTestRule.onNodeWithText("Beta").assertIsDisplayed()
        composeTestRule.onNodeWithText("4").assertIsDisplayed()
        composeTestRule.onNodeWithText("0").assertDoesNotExist()
    }

    @Test
    fun `the list layout shows the same podcasts`() {
        state = state.copy(layout = LibraryLayout.LIST, podcasts = listOf(podcast(1, "Alpha", "Ann", unplayed = 2)))
        setContent()

        composeTestRule.onNodeWithText("Alpha").assertIsDisplayed()
        composeTestRule.onNodeWithText("Ann").assertIsDisplayed()
        composeTestRule.onNodeWithText("2").assertIsDisplayed()
    }

    @Test
    fun `opening a podcast reports its id in either layout`() {
        state = state.copy(podcasts = listOf(podcast(7, "Alpha")))
        setContent()
        composeTestRule.onNodeWithText("Alpha").performClick()

        state = state.copy(layout = LibraryLayout.LIST)
        composeTestRule.onNodeWithText("Alpha").performClick()

        assertEquals(listOf(7L, 7L), opened)
    }

    // --- header controls -----------------------------------------------------------------------

    @Test
    fun `the layout toggle asks for the other layout`() {
        setContent()
        composeTestRule.onNodeWithContentDescription("Show as list").performClick()
        assertEquals(listOf<SubscriptionsIntent>(SubscriptionsIntent.SetLayout(LibraryLayout.LIST)), intents)

        intents.clear()
        state = state.copy(layout = LibraryLayout.LIST)
        composeTestRule.onNodeWithContentDescription("Show as grid").performClick()
        assertEquals(listOf<SubscriptionsIntent>(SubscriptionsIntent.SetLayout(LibraryLayout.GRID)), intents)
    }

    @Test
    fun `the sort chips mark the current sort and choose another`() {
        state = state.copy(sort = LibrarySort.RECENT)
        setContent()

        composeTestRule.onNodeWithText("Recent").assertIsSelected()
        composeTestRule.onNodeWithText("A–Z").performClick()
        composeTestRule.onNodeWithText("Unplayed").performClick()

        assertEquals(
            listOf(SubscriptionsIntent.SetSort(LibrarySort.TITLE), SubscriptionsIntent.SetSort(LibrarySort.UNPLAYED)),
            intents,
        )
    }

    @Test
    fun `the has-new chip toggles the unplayed filter`() {
        setContent()
        composeTestRule.onNodeWithText("Has new").performClick()
        assertEquals(listOf<SubscriptionsIntent>(SubscriptionsIntent.SetUnplayedOnly(true)), intents)

        intents.clear()
        state = state.copy(unplayedOnly = true)
        composeTestRule.onNodeWithText("Has new").assertIsSelected().performClick()
        assertEquals(listOf<SubscriptionsIntent>(SubscriptionsIntent.SetUnplayedOnly(false)), intents)
    }
}
