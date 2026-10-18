// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.downloads

import app.cash.turbine.test
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.sonora.core.database.EpisodeEntity
import org.sonora.core.database.FakeEpisodeDao
import org.sonora.core.database.FakePodcastDao
import org.sonora.core.database.PodcastEntity
import org.sonora.core.download.WaitReason
import org.sonora.core.model.DownloadState
import org.sonora.testutil.FakeDownloadWaits
import org.sonora.testutil.FakeEpisodeActions
import org.sonora.testutil.FakePlaybackPort
import org.sonora.testutil.FakeSettingsStore
import org.sonora.testutil.MainDispatcherExtension
import org.sonora.testutil.awaitUntil

class DownloadsViewModelTest {
    @JvmField @RegisterExtension
    val main = MainDispatcherExtension()

    private val episodes = FakeEpisodeDao()
    private val podcasts = FakePodcastDao()
    private val actions = FakeEpisodeActions()
    private val playback = FakePlaybackPort()
    private val waits = FakeDownloadWaits()
    private val settings = FakeSettingsStore()

    private fun vm() = DownloadsViewModel(episodes, podcasts, actions, playback, waits, settings)

    private suspend fun show(title: String) = podcasts.insert(PodcastEntity(feedUrl = "https://$title", title = title))

    private suspend fun add(
        podcast: Long,
        title: String,
        state: DownloadState,
        published: Long = 0,
        played: Boolean = false,
        bytes: Long = 0,
        total: Long? = null,
    ): Long {
        episodes.insertAll(
            listOf(
                EpisodeEntity(
                    podcastId = podcast, guid = title, title = title, enclosureUrl = "https://e/$title",
                    publishedAtMillis = published, played = played, downloadState = state,
                    downloadedBytes = bytes, sizeBytes = total,
                ),
            ),
        )
        return episodes.rows.value.last().id
    }

    // --- sections ----------------------------------------------------------------------------

    @Test
    fun `splits episodes into active, failed and downloaded and ignores the rest`() = runTest {
        val s = show("Alpha")
        add(s, "queued", DownloadState.QUEUED)
        add(s, "running", DownloadState.DOWNLOADING)
        add(s, "broken", DownloadState.FAILED)
        add(s, "done", DownloadState.DOWNLOADED, bytes = 10)
        add(s, "never", DownloadState.NONE)

        vm().uiState.test {
            val state = awaitUntil { !it.isLoading && it.downloaded.isNotEmpty() }
            assertEquals(setOf("queued", "running"), state.active.map { it.item.title }.toSet())
            assertEquals(listOf("broken"), state.failed.map { it.title })
            assertEquals(listOf("done"), state.downloaded.map { it.title })
        }
    }

    @Test
    fun `running downloads are listed before queued ones`() = runTest {
        val s = show("Alpha")
        add(s, "queued-new", DownloadState.QUEUED, published = 300)
        add(s, "running", DownloadState.DOWNLOADING, published = 100)
        add(s, "queued-old", DownloadState.QUEUED, published = 200)

        vm().uiState.test {
            val state = awaitUntil { it.active.size == 3 }
            assertEquals(listOf("running", "queued-new", "queued-old"), state.active.map { it.item.title })
        }
    }

    @Test
    fun `an empty library is reported as empty once loaded`() = runTest {
        vm().uiState.test {
            val state = awaitUntil { !it.isLoading }
            assertTrue(state.isEmpty)
        }
    }

    // --- sorting -----------------------------------------------------------------------------

    private suspend fun seedDownloaded() {
        val b = show("Beta")
        val a = show("Alpha")
        add(b, "b-old", DownloadState.DOWNLOADED, published = 100, bytes = 500)
        add(a, "a-mid", DownloadState.DOWNLOADED, published = 200, bytes = 900)
        add(b, "b-new", DownloadState.DOWNLOADED, published = 300, bytes = 100)
    }

    @Test
    fun `downloads are newest first by default`() = runTest {
        seedDownloaded()
        vm().uiState.test {
            val state = awaitUntil { it.downloaded.size == 3 }
            assertEquals(DownloadSort.NEWEST, state.sort)
            assertEquals(listOf("b-new", "a-mid", "b-old"), state.downloaded.map { it.title })
        }
    }

    @Test
    fun `can be sorted by size, largest first`() = runTest {
        seedDownloaded()
        val vm = vm()
        vm.uiState.test {
            awaitUntil { it.downloaded.size == 3 }
            vm.onIntent(DownloadsIntent.SetSort(DownloadSort.LARGEST))
            val state = awaitUntil { it.sort == DownloadSort.LARGEST }
            assertEquals(listOf("a-mid", "b-old", "b-new"), state.downloaded.map { it.title })
        }
    }

    @Test
    fun `can be sorted by show, newest first within each`() = runTest {
        seedDownloaded()
        val vm = vm()
        vm.uiState.test {
            awaitUntil { it.downloaded.size == 3 }
            vm.onIntent(DownloadsIntent.SetSort(DownloadSort.SHOW))
            val state = awaitUntil { it.sort == DownloadSort.SHOW }
            assertEquals(listOf("a-mid", "b-new", "b-old"), state.downloaded.map { it.title })
        }
    }

    // --- usage -------------------------------------------------------------------------------

    @Test
    fun `usage totals completed downloads and splits out the played ones`() = runTest {
        settings.maxStorageBytes = 5_000
        val s = show("Alpha")
        add(s, "p1", DownloadState.DOWNLOADED, played = true, bytes = 300)
        add(s, "p2", DownloadState.DOWNLOADED, played = true, bytes = 200)
        add(s, "u1", DownloadState.DOWNLOADED, bytes = 1_000)
        add(s, "partial", DownloadState.DOWNLOADING, bytes = 777)

        vm().uiState.test {
            val usage = awaitUntil { it.downloaded.size == 3 }.usage
            assertEquals(StorageUsage(totalBytes = 1_500, playedBytes = 500, playedCount = 2, limitBytes = 5_000), usage)
            assertEquals(1_000, usage.unplayedBytes)
        }
    }

    @Test
    fun `usage has no limit when none is set`() = runTest {
        vm().uiState.test {
            assertEquals(0L, awaitUntil { !it.isLoading }.usage.limitBytes)
        }
    }

    // --- why it's waiting --------------------------------------------------------------------

    @Test
    fun `active downloads carry the reason they are waiting, when known`() = runTest {
        val s = show("Alpha")
        val waiting = add(s, "waiting", DownloadState.QUEUED)
        val free = add(s, "free", DownloadState.QUEUED)
        waits.reasons.value = mapOf(waiting to WaitReason.WIFI)

        vm().uiState.test {
            val state = awaitUntil { it.active.size == 2 && it.active.any { a -> a.waiting != null } }
            assertEquals(WaitReason.WIFI, state.active.single { it.item.id == waiting }.waiting)
            assertNull(state.active.single { it.item.id == free }.waiting)
        }
    }

    @Test
    fun `the reason updates when conditions change`() = runTest {
        val s = show("Alpha")
        val id = add(s, "waiting", DownloadState.QUEUED)
        waits.reasons.value = mapOf(id to WaitReason.CONNECTION)

        vm().uiState.test {
            awaitUntil { it.active.singleOrNull()?.waiting == WaitReason.CONNECTION }
            waits.reasons.value = emptyMap()
            awaitUntil { it.active.singleOrNull()?.waiting == null }
        }
    }

    // --- intents -----------------------------------------------------------------------------

    @Test
    fun `row actions go to the episode actions and playback`() = runTest {
        val vm = vm()
        vm.uiState.test {
            awaitUntil { !it.isLoading }
            vm.onIntent(DownloadsIntent.Delete(1))
            vm.onIntent(DownloadsIntent.Cancel(2))
            vm.onIntent(DownloadsIntent.Retry(3))
            vm.onIntent(DownloadsIntent.Play(4))
            advanceUntilIdle()
        }
        assertEquals(listOf("delete:1", "cancel:2", "download:3"), actions.calls)
        assertEquals(listOf("play:4"), playback.calls)
    }

    @Test
    fun `deleting all played downloads says how many went`() = runTest {
        actions.playedDeleted = 3
        val vm = vm()
        vm.events.test {
            vm.onIntent(DownloadsIntent.DeleteAllPlayed)
            assertEquals(DownloadsEvent.Message("Deleted 3 played downloads"), awaitItem())
        }
        assertEquals(listOf("deleteAllPlayed"), actions.calls)
    }

    @Test
    fun `a single deletion is worded in the singular`() = runTest {
        actions.playedDeleted = 1
        val vm = vm()
        vm.events.test {
            vm.onIntent(DownloadsIntent.DeleteAllPlayed)
            assertEquals(DownloadsEvent.Message("Deleted 1 played download"), awaitItem())
        }
    }

    @Test
    fun `a failing action is reported instead of crashing`() = runTest {
        actions.failure = IllegalStateException("disk full")
        val vm = vm()
        vm.events.test {
            vm.onIntent(DownloadsIntent.DeleteAllPlayed)
            assertEquals(DownloadsEvent.Message("disk full"), awaitItem())
        }
    }
}
