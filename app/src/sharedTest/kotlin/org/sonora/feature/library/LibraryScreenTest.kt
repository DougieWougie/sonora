// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.library

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
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
    fun `the layout button asks for the next layout`() {
        setContent()
        composeTestRule.onNodeWithContentDescription("Show as list").performClick()
        assertEquals(listOf<SubscriptionsIntent>(SubscriptionsIntent.SetLayout(LibraryLayout.LIST)), intents)

        intents.clear()
        state = state.copy(layout = LibraryLayout.LIST)
        composeTestRule.onNodeWithContentDescription("Show as compact grid").performClick()
        assertEquals(listOf<SubscriptionsIntent>(SubscriptionsIntent.SetLayout(LibraryLayout.COMPACT)), intents)

        intents.clear()
        state = state.copy(layout = LibraryLayout.COMPACT)
        composeTestRule.onNodeWithContentDescription("Show as grid").performClick()
        assertEquals(listOf<SubscriptionsIntent>(SubscriptionsIntent.SetLayout(LibraryLayout.GRID)), intents)
    }

    @Test
    fun `sort and filter live behind one button`() {
        setContent()

        composeTestRule.onNodeWithText("A–Z").assertDoesNotExist()
        composeTestRule.onNodeWithText("Has new").assertDoesNotExist()

        composeTestRule.onNodeWithContentDescription("Sort and filter").performClick()

        composeTestRule.onNodeWithText("A–Z").assertIsDisplayed()
        composeTestRule.onNodeWithText("Recent").assertIsDisplayed()
        composeTestRule.onNodeWithText("Unplayed").assertIsDisplayed()
        composeTestRule.onNodeWithText("Has new").assertIsDisplayed()
    }

    @Test
    fun `the sort menu marks the current sort and chooses another`() {
        state = state.copy(sort = LibrarySort.RECENT)
        setContent()

        composeTestRule.onNodeWithContentDescription("Sort and filter").performClick()
        composeTestRule.onNodeWithText("Recent").assertIsSelected()
        composeTestRule.onNodeWithText("A–Z").assertIsNotSelected().performClick()
        composeTestRule.onNodeWithContentDescription("Sort and filter").performClick()
        composeTestRule.onNodeWithText("Unplayed").performClick()

        assertEquals(
            listOf(SubscriptionsIntent.SetSort(LibrarySort.TITLE), SubscriptionsIntent.SetSort(LibrarySort.UNPLAYED)),
            intents,
        )
    }

    @Test
    fun `choosing a sort closes the menu`() {
        setContent()

        composeTestRule.onNodeWithContentDescription("Sort and filter").performClick()
        composeTestRule.onNodeWithText("Recent").performClick()

        composeTestRule.onNodeWithText("Has new").assertDoesNotExist()
    }

    @Test
    fun `has new in the menu toggles the unplayed filter`() {
        setContent()
        composeTestRule.onNodeWithContentDescription("Sort and filter").performClick()
        composeTestRule.onNodeWithText("Has new").assertIsNotSelected().performClick()
        assertEquals(listOf<SubscriptionsIntent>(SubscriptionsIntent.SetUnplayedOnly(true)), intents)

        intents.clear()
        state = state.copy(unplayedOnly = true)
        composeTestRule.onNodeWithContentDescription("Sort and filter").performClick()
        composeTestRule.onNodeWithText("Has new").assertIsSelected().performClick()
        assertEquals(listOf<SubscriptionsIntent>(SubscriptionsIntent.SetUnplayedOnly(false)), intents)
    }

    // --- search --------------------------------------------------------------------------------

    @Test
    fun `search is an icon until it is opened`() {
        setContent()

        composeTestRule.onNode(hasSetTextAction()).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Search library").performClick()

        composeTestRule.onNode(hasSetTextAction()).assertIsDisplayed()
    }

    @Test
    fun `typing reports the query`() {
        setContent()
        composeTestRule.onNodeWithContentDescription("Search library").performClick()

        composeTestRule.onNode(hasSetTextAction()).performTextInput("sp")

        assertEquals(listOf<SubscriptionsIntent>(SubscriptionsIntent.SetQuery("sp")), intents)
    }

    @Test
    fun `a search already under way opens showing its text`() {
        state = state.copy(query = "space", podcasts = listOf(podcast(1, "Space Talk")))
        setContent()

        composeTestRule.onNode(hasSetTextAction()).assertIsDisplayed()
        composeTestRule.onNodeWithText("space").assertIsDisplayed()
    }

    @Test
    fun `closing the search clears the query and puts the icon back`() {
        state = state.copy(query = "space")
        setContent()

        composeTestRule.onNodeWithContentDescription("Close search").performClick()

        assertEquals(listOf<SubscriptionsIntent>(SubscriptionsIntent.SetQuery("")), intents)
        composeTestRule.onNode(hasSetTextAction()).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Search library").assertIsDisplayed()
    }

    @Test
    fun `a search with no matches says so`() {
        state = state.copy(query = "zzz")
        setContent()

        composeTestRule.onNodeWithText("No shows match “zzz”.").assertIsDisplayed()
        composeTestRule.onNodeWithText("No subscriptions yet", substring = true).assertDoesNotExist()
    }

    // --- per-show actions ----------------------------------------------------------------------

    private fun longPress(title: String) =
        composeTestRule.onNodeWithText(title).performTouchInput { longClick() }

    @Test
    fun `long-pressing a show offers its actions`() {
        state = state.copy(podcasts = listOf(podcast(3, "Alpha", unplayed = 2)))
        setContent()

        longPress("Alpha")

        composeTestRule.onNodeWithText("Mark all played").assertIsDisplayed().assertIsEnabled()
        composeTestRule.onNodeWithText("Turn on auto-download").assertIsDisplayed()
        composeTestRule.onNodeWithText("Unsubscribe").assertIsDisplayed()
        assertEquals(emptyList<Long>(), opened)
    }

    @Test
    fun `each action reports its intent for that show`() {
        state = state.copy(podcasts = listOf(podcast(3, "Alpha", unplayed = 2)))
        setContent()

        longPress("Alpha")
        composeTestRule.onNodeWithText("Mark all played").performClick()
        longPress("Alpha")
        composeTestRule.onNodeWithText("Turn on auto-download").performClick()
        longPress("Alpha")
        composeTestRule.onNodeWithText("Unsubscribe").performClick()

        assertEquals(
            listOf(
                SubscriptionsIntent.MarkAllPlayed(3),
                SubscriptionsIntent.ToggleAutoDownload(3),
                SubscriptionsIntent.RequestUnsubscribe(3),
            ),
            intents,
        )
    }

    @Test
    fun `marking played is unavailable when nothing is unplayed and auto-download reads as on`() {
        state = state.copy(podcasts = listOf(podcast(3, "Alpha", unplayed = 0).copy(autoDownload = true)))
        setContent()

        longPress("Alpha")

        composeTestRule.onNodeWithText("Mark all played").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Turn off auto-download").assertIsDisplayed()
    }

    @Test
    fun `the list layout offers the same actions`() {
        state = state.copy(layout = LibraryLayout.LIST, podcasts = listOf(podcast(3, "Alpha", unplayed = 2)))
        setContent()

        longPress("Alpha")

        composeTestRule.onNodeWithText("Unsubscribe").assertIsDisplayed()
    }

    @Test
    fun `unsubscribing is confirmed by name`() {
        state = state.copy(confirmingUnsubscribe = podcast(3, "Alpha"))
        setContent()

        composeTestRule.onNodeWithText("Unsubscribe from Alpha?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Unsubscribe").performClick()
        composeTestRule.onNodeWithText("Cancel").performClick()

        assertEquals(
            listOf<SubscriptionsIntent>(SubscriptionsIntent.ConfirmUnsubscribe, SubscriptionsIntent.DismissUnsubscribe),
            intents,
        )
    }

    // --- badges --------------------------------------------------------------------------------

    @Test
    fun `a very large unplayed count is capped`() {
        state = state.copy(podcasts = listOf(podcast(1, "Alpha", unplayed = 150)))
        setContent()

        composeTestRule.onNodeWithText("99+").assertIsDisplayed()
        composeTestRule.onNodeWithText("150").assertDoesNotExist()
    }
}
