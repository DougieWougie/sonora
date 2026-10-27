// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.toRoute
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.sonora.feature.player.PlayerUiState

@RunWith(AndroidJUnit4::class)
class AppNavHostTest {
    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var navController: NavHostController

    /** Each screen is a button per navigation callback it is given, so the wiring can be driven by clicks. */
    private val screens = AppScreens(
        subscriptions = { onOpenPodcast, _ -> Button({ onOpenPodcast(42) }) { Text("Subscriptions: open podcast") } },
        queue = { _, onOpenDownloads -> Button(onOpenDownloads) { Text("Queue: open downloads") } },
        discover = { Text("Discover screen") },
        settings = { onOpenDownloads -> Button(onOpenDownloads) { Text("Settings: open downloads") } },
        downloads = { onBack, _ -> Button(onBack) { Text("Downloads: back") } },
        podcast = { onBack, _ -> Button(onBack) { Text("Podcast: back") } },
    )

    private fun setContent() {
        composeTestRule.setContent {
            MaterialExpressiveTheme {
                SonoraScaffold(PlayerUiState(), emptyFlow(), {}, NavigationLayout.BottomBar) { nav, snackbar, modifier ->
                    navController = nav
                    AppNavHost(nav, snackbar, modifier, screens)
                }
            }
        }
    }

    @Test
    fun `it starts on the library`() {
        setContent()

        composeTestRule.onNodeWithText("Subscriptions: open podcast").assertIsDisplayed()
    }

    @Test
    fun `opening a podcast from subscriptions shows it and back returns`() {
        setContent()

        composeTestRule.onNodeWithText("Subscriptions: open podcast").performClick()

        composeTestRule.onNodeWithText("Podcast: back").assertIsDisplayed()
        assertEquals(42L, navController.currentBackStackEntry!!.toRoute<PodcastRoute>().podcastId)

        composeTestRule.onNodeWithText("Podcast: back").performClick()

        composeTestRule.onNodeWithText("Subscriptions: open podcast").assertIsDisplayed()
    }

    @Test
    fun `the queue can open the downloads and back returns`() {
        setContent()
        composeTestRule.onNodeWithText("Queue").performClick()

        composeTestRule.onNodeWithText("Queue: open downloads").performClick()
        composeTestRule.onNodeWithText("Downloads: back").assertIsDisplayed()

        composeTestRule.onNodeWithText("Downloads: back").performClick()
        composeTestRule.onNodeWithText("Queue: open downloads").assertIsDisplayed()
    }

    @Test
    fun `settings can open the downloads and back returns`() {
        setContent()
        composeTestRule.onNodeWithText("Settings").performClick()

        composeTestRule.onNodeWithText("Settings: open downloads").performClick()
        composeTestRule.onNodeWithText("Downloads: back").assertIsDisplayed()

        composeTestRule.onNodeWithText("Downloads: back").performClick()
        composeTestRule.onNodeWithText("Settings: open downloads").assertIsDisplayed()
    }

    @Test
    fun `discover is a destination`() {
        setContent()

        composeTestRule.onNodeWithText("Discover").performClick()

        composeTestRule.onNodeWithText("Discover screen").assertIsDisplayed()
        assertTrue(navController.currentBackStackEntry!!.destination.route!!.contains("DiscoverRoute"))
    }
}
