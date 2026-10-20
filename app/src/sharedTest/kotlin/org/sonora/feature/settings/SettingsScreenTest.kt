// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.sonora.ui.theme.ThemeIntent
import org.sonora.ui.theme.ThemeMode
import org.sonora.ui.theme.ThemePreferences
import org.sonora.ui.theme.ThemeState

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val intents = mutableListOf<SettingsIntent>()
    private val themeIntents = mutableListOf<ThemeIntent>()
    private var state by mutableStateOf(
        SettingsUiState(
            nextcloud = NextcloudState.Idle,
            downloads = DownloadPrefs(unmeteredOnly = true, deleteWhenPlayed = true, autoDownloadPerPodcast = 3, keepPerPodcast = 3),
            allowCleartext = false,
        ),
    )
    private var theme by mutableStateOf(ThemeState(ThemePreferences(ThemeMode.SYSTEM, artworkColors = true)))

    private fun setContent() {
        composeTestRule.setContent {
            MaterialExpressiveTheme {
                SettingsContent(state, theme, { intents += it }, { themeIntents += it }, downloadsLink = { Text("Manage downloads") })
            }
        }
    }

    private fun switchFor(title: String): SemanticsMatcher = isToggleable() and hasText(title)

    private fun toggle(title: String) {
        composeTestRule.onNode(switchFor(title)).performScrollTo().performClick()
    }

    // Chips low on the page are clipped out of the test viewport, where a touch click lands nowhere,
    // so invoke the chip's click action directly.
    private fun choose(text: String) {
        composeTestRule.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun click(text: String) {
        composeTestRule.onNodeWithText(text).performScrollTo().performClick()
    }

    // --- layout --------------------------------------------------------------------------------

    @Test
    fun `every section is on the screen`() {
        setContent()

        listOf(
            "Settings & Sync", "Appearance", "Nextcloud GPodder Sync", "Background sync", "Downloads", "PodcastIndex",
            "Advanced", "About", "Manage downloads", "Free software under the GPL-3.0-or-later",
        ).forEach { composeTestRule.onNodeWithText(it).assertExists(it) }
    }

    // --- appearance ----------------------------------------------------------------------------

    @Test
    fun `the theme chips mark the current mode and choose another`() {
        theme = ThemeState(ThemePreferences(ThemeMode.DARK))
        setContent()

        composeTestRule.onNodeWithText("Dark").assertIsSelected()
        click("AMOLED")
        click("Light")
        click("System")

        assertEquals(
            listOf(ThemeIntent.SelectMode(ThemeMode.AMOLED), ThemeIntent.SelectMode(ThemeMode.LIGHT), ThemeIntent.SelectMode(ThemeMode.SYSTEM)),
            themeIntents,
        )
    }

    @Test
    fun `the artwork colours switch toggles`() {
        setContent()

        toggle("Colors from artwork")

        assertEquals(listOf<ThemeIntent>(ThemeIntent.ToggleArtworkColors), themeIntents)
    }

    // --- sync and downloads --------------------------------------------------------------------

    @Test
    fun `the sync interval is chosen from the chips`() {
        state = state.copy(syncIntervalHours = 6)
        setContent()

        composeTestRule.onNodeWithText("6 h").assertIsSelected()
        click("12 h")
        click("Off")

        assertEquals(listOf(SettingsIntent.SetSyncInterval(12), SettingsIntent.SetSyncInterval(0)), intents)
    }

    @Test
    fun `the download switches report their new value`() {
        setContent()

        toggle("Auto-download on Wi-Fi only")
        toggle("Auto-download only while charging")
        toggle("Delete downloads once played")

        assertEquals(
            listOf(
                SettingsIntent.SetUnmeteredOnly(false),
                SettingsIntent.SetChargingOnly(true),
                SettingsIntent.SetDeleteWhenPlayed(false),
            ),
            intents,
        )
    }

    @Test
    fun `the download limits are chosen from the chips`() {
        setContent()

        choose("2") // only the auto-download choices offer 2
        choose("All") // only the keep choices offer All
        choose("5 GB")

        assertEquals(
            listOf(SettingsIntent.SetAutoDownloadCount(2), SettingsIntent.SetKeepPerPodcast(0), SettingsIntent.SetMaxStorage(5_000_000_000)),
            intents,
        )
    }

    // --- PodcastIndex --------------------------------------------------------------------------

    @Test
    fun `PodcastIndex credentials are typed, saved and removed`() {
        setContent()

        composeTestRule.onNode(hasSetTextAction() and hasText("API key")).performScrollTo().performTextInput("KEY")
        state = state.copy(podcastIndex = PodcastIndexForm("KEY", ""))  // as the ViewModel would reflect the edit
        composeTestRule.onNode(hasSetTextAction() and hasText("API secret")).performScrollTo().performTextInput("SECRET")
        composeTestRule.onNodeWithText("Save").performScrollTo().assertIsEnabled().performClick()
        click("Remove")

        assertEquals(
            listOf(
                SettingsIntent.EditPodcastIndexKey("KEY"), SettingsIntent.EditPodcastIndexSecret("SECRET"),
                SettingsIntent.SavePodcastIndex, SettingsIntent.ClearPodcastIndex,
            ),
            intents,
        )
    }

    @Test
    fun `saved PodcastIndex credentials show as saved`() {
        state = state.copy(podcastIndex = PodcastIndexForm("KEY", "SECRET", saved = true))
        setContent()

        composeTestRule.onNodeWithText("Saved").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Save").assertDoesNotExist()
    }

    // --- advanced ------------------------------------------------------------------------------

    @Test
    fun `the cleartext switch reports its new value and a restart is only asked for after a change`() {
        setContent()
        composeTestRule.onNodeWithText("Restart Sonora", substring = true).assertDoesNotExist()

        toggle("Allow cleartext (HTTP)")
        state = state.copy(allowCleartext = true, restartRequired = true)

        assertEquals(listOf<SettingsIntent>(SettingsIntent.SetAllowCleartext(true)), intents)
        composeTestRule.onNodeWithText("Restart Sonora for this change to take effect.").assertExists()
    }

    // --- Nextcloud -----------------------------------------------------------------------------

    @Test
    fun `the Nextcloud card follows the account state`() {
        setContent()
        composeTestRule.onNodeWithText("Connect").assertExists()

        state = state.copy(nextcloud = NextcloudState.Connected("alice", "https://cloud.example.com", lastSyncTime = null))

        composeTestRule.onNodeWithText("alice@cloud.example.com").assertExists()
        composeTestRule.onNodeWithText("Not synced yet").assertExists()
    }
}
