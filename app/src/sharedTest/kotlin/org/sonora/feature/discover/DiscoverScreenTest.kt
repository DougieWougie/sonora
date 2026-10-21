// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.discover

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.sonora.core.model.DirectoryPodcast

@RunWith(AndroidJUnit4::class)
class DiscoverScreenTest {
    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val intents = mutableListOf<DiscoverIntent>()
    private var imports = 0
    private var exports = 0
    private var state by mutableStateOf(DiscoverUiState())

    private val space = DirectoryPodcast("Space Talk", "Neil", "https://space/feed", null)

    private fun setContent() {
        composeTestRule.setContent {
            MaterialExpressiveTheme {
                DiscoverContent(state, { intents += it }, onImportOpml = { imports++ }, onExportOpml = { exports++ })
            }
        }
    }

    // --- search --------------------------------------------------------------------------------

    @Test
    fun `before searching the screen explains the options`() {
        setContent()

        composeTestRule.onNodeWithText("Discover").assertIsDisplayed()
        composeTestRule.onNodeWithText("Search a directory", substring = true).assertIsDisplayed()
    }

    @Test
    fun `the hint goes once there are results`() {
        state = state.copy(query = "space", results = listOf(DiscoverResult(space, subscribed = false)))
        setContent()

        composeTestRule.onNodeWithText("Search a directory", substring = true).assertDoesNotExist()
    }

    @Test
    fun `typing reports the query`() {
        setContent()

        composeTestRule.onNode(hasSetTextAction()).performTextInput("space")

        assertEquals(listOf<DiscoverIntent>(DiscoverIntent.QueryChanged("space")), intents)
    }

    @Test
    fun `the directory chips mark the source and choose another`() {
        setContent()

        composeTestRule.onNodeWithText("Apple Podcasts").assertIsSelected()
        composeTestRule.onNodeWithText("PodcastIndex").performClick()

        assertEquals(listOf<DiscoverIntent>(DiscoverIntent.SelectDirectory(DirectorySource.PODCAST_INDEX)), intents)
    }

    @Test
    fun `a search error is shown`() {
        state = state.copy(error = "Search failed")
        setContent()

        composeTestRule.onNodeWithText("Search failed").assertIsDisplayed()
    }

    // --- suggestions ---------------------------------------------------------------------------

    @Test
    fun `suggestions are listed under a heading while nothing is typed, and can be subscribed to`() {
        state = state.copy(suggestions = listOf(DiscoverResult(space, subscribed = false)))
        setContent()

        composeTestRule.onNodeWithText("Suggested podcasts").assertIsDisplayed()
        composeTestRule.onNodeWithText("Space Talk").assertIsDisplayed()
        composeTestRule.onNodeWithText("Search a directory", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Subscribe").performClick()

        assertEquals(listOf<DiscoverIntent>(DiscoverIntent.Subscribe(space)), intents)
    }

    @Test
    fun `a subscribed suggestion shows as added`() {
        state = state.copy(suggestions = listOf(DiscoverResult(space, subscribed = true)))
        setContent()

        composeTestRule.onNodeWithText("Added").assertIsNotEnabled()
    }

    @Test
    fun `suggestions give way to search results once the user types`() {
        val other = DirectoryPodcast("Other Show", null, "https://other/feed", null)
        state = state.copy(
            query = "other",
            suggestions = listOf(DiscoverResult(space, subscribed = false)),
            results = listOf(DiscoverResult(other, subscribed = false)),
        )
        setContent()

        composeTestRule.onNodeWithText("Other Show").assertIsDisplayed()
        composeTestRule.onNodeWithText("Space Talk").assertDoesNotExist()
        composeTestRule.onNodeWithText("Suggested podcasts").assertDoesNotExist()
    }

    // --- results -------------------------------------------------------------------------------

    @Test
    fun `a result can be subscribed to`() {
        state = state.copy(query = "space", results = listOf(DiscoverResult(space, subscribed = false)))
        setContent()

        composeTestRule.onNodeWithText("Space Talk").assertIsDisplayed()
        composeTestRule.onNodeWithText("Neil").assertIsDisplayed()
        composeTestRule.onNodeWithText("Subscribe").assertIsEnabled().performClick()

        assertEquals(listOf<DiscoverIntent>(DiscoverIntent.Subscribe(space)), intents)
    }

    @Test
    fun `a subscribed result shows as added and cannot be subscribed again`() {
        state = state.copy(query = "space", results = listOf(DiscoverResult(space, subscribed = true)))
        setContent()

        composeTestRule.onNodeWithText("Added").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Subscribe").assertDoesNotExist()
    }

    // --- quick actions -------------------------------------------------------------------------

    @Test
    fun `the quick actions ask for the url dialog and the OPML pickers`() {
        setContent()

        composeTestRule.onNodeWithText("Add by URL").performClick()
        composeTestRule.onNodeWithText("Import OPML").performClick()
        composeTestRule.onNodeWithText("Export OPML").performClick()

        assertEquals(listOf<DiscoverIntent>(DiscoverIntent.ShowAddUrl), intents)
        assertEquals(1, imports)
        assertEquals(1, exports)
    }

    // --- add-by-url dialog ---------------------------------------------------------------------

    @Test
    fun `no dialog is shown until asked for`() {
        setContent()

        composeTestRule.onNodeWithText("Add by feed URL").assertDoesNotExist()
    }

    // The dialog's parts are rendered in the main window: a text field inside a dialog window never goes
    // idle under Robolectric.
    private fun setDialogParts(dialog: AddUrlState) {
        composeTestRule.mainClock.autoAdvance = dialog.isSubmitting.not()
        composeTestRule.setContent {
            MaterialExpressiveTheme {
                Column {
                    AddUrlFields(dialog) { intents += it }
                    AddUrlSubscribeButton(dialog) { intents += it }
                    AddUrlCancelButton { intents += it }
                }
            }
        }
    }

    @Test
    fun `the dialog submits, edits and cancels`() {
        setDialogParts(AddUrlState())

        composeTestRule.onNodeWithText("https://example.com/feed.xml").performTextInput("https://a/feed")
        composeTestRule.onNodeWithText("Subscribe").assertIsEnabled().performClick()
        composeTestRule.onNodeWithText("Cancel").performClick()

        assertEquals(
            listOf(DiscoverIntent.AddUrlChanged("https://a/feed"), DiscoverIntent.SubmitUrl, DiscoverIntent.DismissAddUrl),
            intents,
        )
    }

    @Test
    fun `the dialog shows its error`() {
        setDialogParts(AddUrlState(url = "http://a", error = "Only HTTPS feeds are allowed"))

        composeTestRule.onNodeWithText("Only HTTPS feeds are allowed").assertIsDisplayed()
    }

    @Test
    fun `the dialog cannot be submitted twice`() {
        setDialogParts(AddUrlState(url = "https://a/feed", isSubmitting = true))

        composeTestRule.onNodeWithText("Subscribe").assertIsNotEnabled()
    }
}
