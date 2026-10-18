// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.downloads

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.sonora.core.database.EpisodeEntity
import org.sonora.core.database.FakeEpisodeDao
import org.sonora.core.model.DownloadState
import org.sonora.testutil.FakeSettingsStore
import org.sonora.testutil.MainDispatcherExtension
import org.sonora.testutil.awaitUntil

class DownloadsSummaryViewModelTest {
    @JvmField @RegisterExtension
    val main = MainDispatcherExtension()

    private val episodes = FakeEpisodeDao()
    private val settings = FakeSettingsStore()
    private val notices = org.sonora.testutil.FakeEvictionNotices()

    private suspend fun add(guid: String, state: DownloadState, bytes: Long, played: Boolean = false) {
        episodes.insertAll(
            listOf(
                EpisodeEntity(
                    podcastId = 1, guid = guid, title = guid, enclosureUrl = "https://e/$guid",
                    downloadState = state, downloadedBytes = bytes, played = played,
                ),
            ),
        )
    }

    @Test
    fun `summarises completed downloads and the limit`() = runTest {
        settings.maxStorageBytes = 9_000
        add("a", DownloadState.DOWNLOADED, 400, played = true)
        add("b", DownloadState.DOWNLOADED, 600)
        add("c", DownloadState.DOWNLOADING, 50)

        DownloadsSummaryViewModel(episodes, settings, notices).usage.test {
            val usage = awaitUntil { it.downloadedCount == 2 }
            assertEquals(StorageUsage(totalBytes = 1_000, playedBytes = 400, playedCount = 1, limitBytes = 9_000, downloadedCount = 2), usage)
        }
    }

    @Test
    fun `follows new downloads`() = runTest {
        DownloadsSummaryViewModel(episodes, settings, notices).usage.test {
            assertEquals(0, awaitItem().downloadedCount)
            add("a", DownloadState.DOWNLOADED, 250)
            assertEquals(250L, awaitUntil { it.downloadedCount == 1 }.totalBytes)
        }
    }

    @Test
    fun `reports unplayed episodes retention removed, until dismissed`() = runTest {
        DownloadsSummaryViewModel(episodes, settings, notices).evictedUnplayed.test {
            assertEquals(0, awaitItem())
            notices.record(3)
            assertEquals(3, awaitUntil { it == 3 })
            notices.dismiss()
            assertEquals(0, awaitUntil { it == 0 })
        }
    }
}
