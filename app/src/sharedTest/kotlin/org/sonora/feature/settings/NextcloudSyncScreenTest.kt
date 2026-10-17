// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.sonora.core.testing.TestTags

@RunWith(AndroidJUnit4::class)
class NextcloudSyncScreenTest {
    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val intents = mutableListOf<SettingsIntent>()
    private var nextcloud by mutableStateOf<NextcloudState>(NextcloudState.Idle)
    private var isSyncing by mutableStateOf(false)

    private fun setContent() {
        composeTestRule.setContent {
            MaterialExpressiveTheme {
                NextcloudCard(
                    SettingsUiState(
                        nextcloud = nextcloud,
                        isSyncing = isSyncing,
                        downloads = DownloadPrefs(unmeteredOnly = true, deleteWhenPlayed = false),
                        allowCleartext = false,
                    ),
                ) { intents += it }
            }
        }
    }

    // --- step 1: idle ------------------------------------------------------------------------

    @Test
    fun connectStaysDisabledUntilAServerIsEntered() {
        setContent()

        composeTestRule.onNodeWithTag(TestTags.NEXTCLOUD_SERVER_INPUT).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TestTags.NEXTCLOUD_CONNECT_BUTTON).assertIsNotEnabled()

        composeTestRule.onNodeWithTag(TestTags.NEXTCLOUD_SERVER_INPUT).performTextInput("   ")
        composeTestRule.onNodeWithTag(TestTags.NEXTCLOUD_CONNECT_BUTTON).assertIsNotEnabled()
    }

    @Test
    fun connectStartsTheLoginForTheEnteredServer() {
        setContent()

        composeTestRule.onNodeWithTag(TestTags.NEXTCLOUD_SERVER_INPUT).performTextInput("https://cloud.example.com")
        composeTestRule.onNodeWithTag(TestTags.NEXTCLOUD_CONNECT_BUTTON).assertIsEnabled().performClick()

        assertEquals(listOf<SettingsIntent>(SettingsIntent.StartLogin("https://cloud.example.com")), intents)
    }

    // --- step 2: browser approval ------------------------------------------------------------

    @Test
    fun waitingForTheBrowserCanBeCancelledOrConfirmed() {
        nextcloud = NextcloudState.WaitingForBrowserAuth("https://cloud.example.com/index.php/login/v2/flow/xyz")
        setContent()

        composeTestRule.onNodeWithTag(TestTags.NEXTCLOUD_CANCEL_POLLING_BUTTON).performClick()

        assertEquals(listOf<SettingsIntent>(SettingsIntent.CancelLogin), intents)
    }

    @Test
    fun pollingShowsAnIndicatorAndCanBeCancelled() {
        nextcloud = NextcloudState.Polling
        // The loading indicator animates forever, so the clock must not chase an idle that never comes.
        composeTestRule.mainClock.autoAdvance = false
        setContent()

        composeTestRule.onNodeWithTag(TestTags.NEXTCLOUD_POLLING_INDICATOR).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TestTags.NEXTCLOUD_SERVER_INPUT).assertDoesNotExist()
        composeTestRule.onNodeWithTag(TestTags.NEXTCLOUD_CANCEL_POLLING_BUTTON).performClick()

        assertEquals(listOf<SettingsIntent>(SettingsIntent.CancelLogin), intents)
    }

    @Test
    fun theLoginUrlIsOpenedOnceAndThePollStartsOnlyIfTheBrowserOpened() {
        val events = Channel<SettingsEvent>(Channel.BUFFERED)
        val opened = mutableListOf<String>()
        composeTestRule.setContent {
            OpenLoginUrlEffect(events.receiveAsFlow(), open = { opened += it; true }, onLaunched = { intents += SettingsIntent.BrowserLaunched })
        }

        events.trySend(SettingsEvent.OpenLoginUrl("https://cloud.example.com/index.php/login/v2/flow/xyz"))
        composeTestRule.waitForIdle()

        assertEquals(listOf("https://cloud.example.com/index.php/login/v2/flow/xyz"), opened)
        assertEquals(listOf<SettingsIntent>(SettingsIntent.BrowserLaunched), intents)
    }

    @Test
    fun noPollStartsWhenNoBrowserCouldBeOpened() {
        val events = Channel<SettingsEvent>(Channel.BUFFERED)
        composeTestRule.setContent {
            OpenLoginUrlEffect(events.receiveAsFlow(), open = { false }, onLaunched = { intents += SettingsIntent.BrowserLaunched })
        }

        events.trySend(SettingsEvent.OpenLoginUrl("https://cloud.example.com/login"))
        composeTestRule.waitForIdle()

        assertEquals(emptyList<SettingsIntent>(), intents)
    }

    // --- step 3: connected -------------------------------------------------------------------

    @Test
    fun connectedShowsTheAccountInPlaceOfTheLoginForm() {
        nextcloud = NextcloudState.Connected("douglas", "https://cloud.example.com", lastSyncTime = null)
        setContent()

        composeTestRule.onNodeWithTag(TestTags.NEXTCLOUD_SERVER_INPUT).assertDoesNotExist()
        composeTestRule.onNodeWithTag(TestTags.NEXTCLOUD_CONNECTED_BADGE).assertTextContains("douglas@cloud.example.com")

        composeTestRule.onNodeWithTag(TestTags.NEXTCLOUD_SYNC_NOW_BUTTON).performClick()
        assertEquals(listOf<SettingsIntent>(SettingsIntent.SyncNow), intents)
    }

    @Test
    fun syncNowIsDisabledWhileSyncing() {
        nextcloud = NextcloudState.Connected("douglas", "https://cloud.example.com", lastSyncTime = 1_700_000_000_000)
        isSyncing = true
        composeTestRule.mainClock.autoAdvance = false
        setContent()

        composeTestRule.onNodeWithTag(TestTags.NEXTCLOUD_SYNC_NOW_BUTTON).assertIsNotEnabled()
    }

    // --- step 4: error and retry -------------------------------------------------------------

    @Test
    fun anErrorShowsABannerWithARetryThatRestartsTheLogin() {
        setContent()
        composeTestRule.onNodeWithTag(TestTags.NEXTCLOUD_SERVER_INPUT).performTextInput("https://cloud.example.com")

        nextcloud = NextcloudState.Error("HTTP 401: Polling timed out")

        composeTestRule.onNodeWithTag(TestTags.NEXTCLOUD_ERROR_BANNER).assert(hasAnyDescendant(hasText("HTTP 401: Polling timed out")))
        composeTestRule.onNodeWithTag(TestTags.NEXTCLOUD_RETRY_BUTTON).performClick()

        assertEquals(listOf<SettingsIntent>(SettingsIntent.StartLogin("https://cloud.example.com")), intents)
    }

    @Test
    fun retryIsUnavailableWithoutAServerToRetry() {
        nextcloud = NextcloudState.Error("Enter your Nextcloud server address")
        setContent()

        composeTestRule.onNodeWithTag(TestTags.NEXTCLOUD_ERROR_BANNER).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TestTags.NEXTCLOUD_RETRY_BUTTON).assertIsNotEnabled()
    }
}
