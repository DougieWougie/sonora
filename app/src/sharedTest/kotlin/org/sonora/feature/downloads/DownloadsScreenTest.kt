// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.downloads

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.sonora.core.download.WaitReason
import org.sonora.core.model.DownloadState
import org.sonora.core.testing.TestTags
import org.sonora.feature.common.EpisodeItem

@RunWith(AndroidJUnit4::class)
class DownloadsScreenTest {
    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val intents = mutableListOf<DownloadsIntent>()
    private var state by mutableStateOf(DownloadsUiState(isLoading = false))
    private var backs = 0

    private fun item(id: Long, state: DownloadState, played: Boolean = false, bytes: Long = 0, total: Long? = null) = EpisodeItem(
        id = id, podcastId = 1, podcastTitle = "Show", title = "Episode $id", publishedAtMillis = null,
        durationSeconds = null, positionSeconds = 0, played = played, downloadState = state,
        downloadedBytes = bytes, sizeBytes = total, artworkUrl = null, queued = false,
    )

    private fun setContent() {
        composeTestRule.setContent {
            MaterialExpressiveTheme {
                DownloadsContent(state, onBack = { backs++ }, onIntent = { intents += it })
            }
        }
    }

    // --- empty & usage -----------------------------------------------------------------------

    @Test
    fun anEmptyLibrarySaysSo() {
        setContent()

        composeTestRule.onNodeWithTag(TestTags.DOWNLOADS_EMPTY).assertIsDisplayed()
    }

    @Test
    fun theUsageSummaryShowsTheTotalAndLimit() {
        state = DownloadsUiState(
            isLoading = false,
            usage = StorageUsage(totalBytes = 1_400_000_000, limitBytes = 5_000_000_000, downloadedCount = 2),
            downloaded = listOf(item(1, DownloadState.DOWNLOADED, bytes = 700_000_000), item(2, DownloadState.DOWNLOADED, bytes = 700_000_000)),
        )
        setContent()

        composeTestRule.onNodeWithTag(TestTags.DOWNLOADS_USAGE_SUMMARY).assertIsDisplayed()
        composeTestRule.onNodeWithText("1.4 GB of 5 GB · 2 episodes").assertIsDisplayed()
    }

    @Test
    fun backIsReachable() {
        setContent()

        composeTestRule.onNodeWithText("Downloads").assertIsDisplayed()
        composeTestRule.onNodeWithTag(TestTags.DOWNLOADS_BACK).performClick()

        assertEquals(1, backs)
    }

    // --- active downloads --------------------------------------------------------------------

    @Test
    fun waitingDownloadsSayWhy() {
        state = DownloadsUiState(
            isLoading = false,
            active = listOf(
                ActiveDownload(item(1, DownloadState.QUEUED), WaitReason.WIFI),
                ActiveDownload(item(2, DownloadState.QUEUED), WaitReason.CHARGER),
                ActiveDownload(item(3, DownloadState.QUEUED), WaitReason.CONNECTION),
                ActiveDownload(item(4, DownloadState.QUEUED), null),
            ),
        )
        setContent()

        composeTestRule.onNodeWithText("Waiting for Wi-Fi").assertIsDisplayed()
        composeTestRule.onNodeWithText("Waiting for charger").assertIsDisplayed()
        composeTestRule.onNodeWithText("Waiting for a connection").assertIsDisplayed()
        composeTestRule.onNodeWithText("Queued").assertIsDisplayed()
    }

    @Test
    fun aRunningDownloadShowsHowMuchHasArrived() {
        state = DownloadsUiState(
            isLoading = false,
            active = listOf(ActiveDownload(item(1, DownloadState.DOWNLOADING, bytes = 31_000_000, total = 74_000_000), null)),
        )
        setContent()

        composeTestRule.onNodeWithText("31 MB of 74 MB").assertIsDisplayed()
    }

    @Test
    fun anActiveDownloadCanBeCancelled() {
        state = DownloadsUiState(isLoading = false, active = listOf(ActiveDownload(item(7, DownloadState.QUEUED), null)))
        setContent()

        composeTestRule.onNodeWithTag(TestTags.downloadCancel(7)).performClick()

        assertEquals(listOf<DownloadsIntent>(DownloadsIntent.Cancel(7)), intents)
    }

    // --- failed ------------------------------------------------------------------------------

    @Test
    fun aFailedDownloadCanBeRetriedOrDismissed() {
        state = DownloadsUiState(isLoading = false, failed = listOf(item(5, DownloadState.FAILED)))
        setContent()

        composeTestRule.onNodeWithText("Download failed").assertIsDisplayed()
        composeTestRule.onNodeWithTag(TestTags.downloadRetry(5)).performClick()
        composeTestRule.onNodeWithTag(TestTags.downloadDelete(5)).performClick()

        assertEquals(listOf<DownloadsIntent>(DownloadsIntent.Retry(5), DownloadsIntent.Delete(5)), intents)
    }

    // --- downloaded --------------------------------------------------------------------------

    @Test
    fun downloadedRowsShowSizeAndPlayedState() {
        state = DownloadsUiState(
            isLoading = false,
            downloaded = listOf(
                item(1, DownloadState.DOWNLOADED, played = true, bytes = 48_000_000),
                item(2, DownloadState.DOWNLOADED, played = false, bytes = 62_000_000),
            ),
        )
        setContent()

        composeTestRule.onNodeWithText("48 MB · Played").assertIsDisplayed()
        composeTestRule.onNodeWithText("62 MB · Unplayed").assertIsDisplayed()
    }

    @Test
    fun tappingADownloadPlaysItAndTheBinDeletesIt() {
        state = DownloadsUiState(isLoading = false, downloaded = listOf(item(9, DownloadState.DOWNLOADED, bytes = 1_000)))
        setContent()

        composeTestRule.onNodeWithTag(TestTags.downloadRow(9)).performClick()
        composeTestRule.onNodeWithTag(TestTags.downloadDelete(9)).performClick()

        assertEquals(listOf<DownloadsIntent>(DownloadsIntent.Play(9), DownloadsIntent.Delete(9)), intents)
    }

    @Test
    fun theSortOrderCanBeChanged() {
        state = DownloadsUiState(isLoading = false, downloaded = listOf(item(1, DownloadState.DOWNLOADED, bytes = 1_000)))
        setContent()

        composeTestRule.onNodeWithTag(TestTags.downloadSort("LARGEST")).performClick()

        assertEquals(listOf<DownloadsIntent>(DownloadsIntent.SetSort(DownloadSort.LARGEST)), intents)
    }

    // --- delete all played -------------------------------------------------------------------

    private fun withPlayed() {
        state = DownloadsUiState(
            isLoading = false,
            usage = StorageUsage(totalBytes = 1_000_000, playedBytes = 600_000, playedCount = 2, downloadedCount = 3),
            downloaded = listOf(item(1, DownloadState.DOWNLOADED, played = true, bytes = 600_000)),
        )
    }

    @Test
    fun deleteAllPlayedIsOfferedOnlyWhenThereAreSome() {
        state = DownloadsUiState(isLoading = false, usage = StorageUsage(totalBytes = 5, downloadedCount = 1))
        setContent()

        composeTestRule.onNodeWithTag(TestTags.DOWNLOADS_DELETE_PLAYED_BUTTON).assertDoesNotExist()
    }

    @Test
    fun deletingAllPlayedAsksForConfirmationFirst() {
        withPlayed()
        setContent()

        composeTestRule.onNodeWithTag(TestTags.DOWNLOADS_DELETE_PLAYED_BUTTON).performClick()

        composeTestRule.onNodeWithText("Delete 2 played downloads? This frees 600 KB.").assertIsDisplayed()
        assertEquals(emptyList<DownloadsIntent>(), intents)

        composeTestRule.onNodeWithTag(TestTags.DOWNLOADS_CONFIRM_DELETE_PLAYED).performClick()
        assertEquals(listOf<DownloadsIntent>(DownloadsIntent.DeleteAllPlayed), intents)
    }

    @Test
    fun decliningTheConfirmationDeletesNothing() {
        withPlayed()
        setContent()

        composeTestRule.onNodeWithTag(TestTags.DOWNLOADS_DELETE_PLAYED_BUTTON).performClick()
        composeTestRule.onNodeWithTag(TestTags.DOWNLOADS_DISMISS_DELETE_PLAYED).performClick()

        assertEquals(emptyList<DownloadsIntent>(), intents)
    }

    // --- eviction notice ---------------------------------------------------------------------

    @Test
    fun theBannerSaysHowManyUnplayedEpisodesWereRemoved() {
        state = DownloadsUiState(
            isLoading = false,
            evictedUnplayed = 3,
            downloaded = listOf(item(1, DownloadState.DOWNLOADED, bytes = 1_000)),
        )
        setContent()

        composeTestRule.onNodeWithTag(TestTags.DOWNLOADS_EVICTION_BANNER).assertIsDisplayed()
        composeTestRule.onNodeWithText("3 unplayed episodes were removed to stay within your limits.").assertIsDisplayed()
    }

    @Test
    fun theBannerIsWordedInTheSingular() {
        state = DownloadsUiState(isLoading = false, evictedUnplayed = 1)
        setContent()

        composeTestRule.onNodeWithText("1 unplayed episode was removed to stay within your limits.").assertIsDisplayed()
    }

    @Test
    fun noBannerWhenNothingUnplayedWasRemoved() {
        state = DownloadsUiState(isLoading = false, downloaded = listOf(item(1, DownloadState.DOWNLOADED, bytes = 1_000)))
        setContent()

        composeTestRule.onNodeWithTag(TestTags.DOWNLOADS_EVICTION_BANNER).assertDoesNotExist()
    }

    @Test
    fun theBannerStillShowsWhenEverythingWasRemoved() {
        state = DownloadsUiState(isLoading = false, evictedUnplayed = 2)
        setContent()

        composeTestRule.onNodeWithTag(TestTags.DOWNLOADS_EVICTION_BANNER).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TestTags.DOWNLOADS_EMPTY).assertIsDisplayed()
    }

    @Test
    fun theBannerCanBeDismissed() {
        state = DownloadsUiState(isLoading = false, evictedUnplayed = 2)
        setContent()

        composeTestRule.onNodeWithTag(TestTags.DOWNLOADS_EVICTION_DISMISS).performClick()

        assertEquals(listOf<DownloadsIntent>(DownloadsIntent.DismissEvictionNotice), intents)
    }

    // --- entry point -------------------------------------------------------------------------

    @Test
    fun theLinkShowsTheUsageAndOpensTheScreen() {
        var opened = 0
        composeTestRule.setContent {
            MaterialExpressiveTheme {
                DownloadsLinkContent(StorageUsage(totalBytes = 1_400_000_000, limitBytes = 5_000_000_000, downloadedCount = 12), evictedUnplayed = 0, onClick = { opened++ })
            }
        }

        composeTestRule.onNodeWithText("Manage downloads").assertIsDisplayed()
        composeTestRule.onNodeWithText("1.4 GB of 5 GB · 12 episodes").assertIsDisplayed()
        composeTestRule.onNodeWithTag(TestTags.DOWNLOADS_LINK).performClick()

        assertEquals(1, opened)
    }

    @Test
    fun theLinkFlagsUnplayedEpisodesThatWereRemoved() {
        composeTestRule.setContent {
            MaterialExpressiveTheme {
                DownloadsLinkContent(StorageUsage(downloadedCount = 1, totalBytes = 5), evictedUnplayed = 3, onClick = {})
            }
        }

        // The row merges its children for accessibility, so the badge is only a separate node unmerged.
        val badge = composeTestRule.onNodeWithTag(TestTags.DOWNLOADS_LINK_BADGE, useUnmergedTree = true)
        badge.assertIsDisplayed().assertContentDescriptionEquals("3 unplayed episodes removed")
        composeTestRule.onNodeWithText("3", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun theLinkHasNoFlagWhenNothingWasRemoved() {
        composeTestRule.setContent {
            MaterialExpressiveTheme {
                DownloadsLinkContent(StorageUsage(downloadedCount = 1, totalBytes = 5), evictedUnplayed = 0, onClick = {})
            }
        }

        composeTestRule.onNodeWithTag(TestTags.DOWNLOADS_LINK_BADGE, useUnmergedTree = true).assertDoesNotExist()
    }
}
