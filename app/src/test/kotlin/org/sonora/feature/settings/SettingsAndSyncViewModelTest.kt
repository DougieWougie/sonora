// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.settings

import app.cash.turbine.test
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.sonora.core.network.LoginFlowException
import org.sonora.core.network.LoginFlowSession
import org.sonora.core.network.NextcloudCredentials
import org.sonora.core.network.discovery.PodcastIndexCredentials
import org.sonora.testutil.FakeLoginFlow
import org.sonora.testutil.FakeSettingsStore
import org.sonora.testutil.FakeSyncController
import org.sonora.testutil.MainDispatcherExtension

class SettingsAndSyncViewModelTest {
    @JvmField @RegisterExtension
    val main = MainDispatcherExtension()

    private val login = FakeLoginFlow()
    private val settings = FakeSettingsStore()
    private val sync = FakeSyncController()
    private val creds = NextcloudCredentials("https://cloud.example", "alice", "app-pass")

    private fun vm() = SettingsAndSyncViewModel(login, settings, sync)
    private val SettingsAndSyncViewModel.nextcloud get() = uiState.value.nextcloud

    // --- initial state -----------------------------------------------------------------------

    @Test
    fun `starts idle when no account is stored`() = runTest {
        val vm = vm(); runCurrent()
        assertEquals(NextcloudState.Idle, vm.nextcloud)
    }

    @Test
    fun `starts connected, with the last sync time, when an account is stored`() = runTest {
        settings.account = NextcloudAccount("https://cloud.example", "alice", "pw")
        settings.lastSyncMillis.value = 1_700_000_000_000
        val vm = vm(); runCurrent()
        assertEquals(NextcloudState.Connected("alice", "https://cloud.example", 1_700_000_000_000), vm.nextcloud)
    }

    @Test
    fun `connected shows no sync time until a sync has succeeded`() = runTest {
        settings.account = NextcloudAccount("https://cloud.example", "alice", "pw")
        val vm = vm(); runCurrent()
        assertEquals(NextcloudState.Connected("alice", "https://cloud.example", null), vm.nextcloud)
    }

    // --- login flow --------------------------------------------------------------------------

    @Test
    fun `starting login asks the server for a login url and waits for the browser`() = runTest {
        val vm = vm()
        vm.events.test {
            vm.onIntent(SettingsIntent.StartLogin("cloud.example"))
            advanceUntilIdle()
            assertEquals(NextcloudState.WaitingForBrowserAuth("https://cloud/login/flow"), vm.nextcloud)
            assertEquals(SettingsEvent.OpenLoginUrl("https://cloud/login/flow"), awaitItem())
        }
        assertEquals(listOf("https://cloud.example"), login.startedFor)
    }

    @Test
    fun `an invalid server url is an error and nothing is requested`() = runTest {
        val vm = vm()
        vm.onIntent(SettingsIntent.StartLogin("   "))
        advanceUntilIdle()
        assertTrue(vm.nextcloud is NextcloudState.Error)
        assertTrue(login.startedFor.isEmpty())
    }

    @Test
    fun `a cleartext server is refused unless cleartext is allowed`() = runTest {
        val vm = vm()
        vm.onIntent(SettingsIntent.StartLogin("http://192.168.1.5"))
        advanceUntilIdle()
        assertTrue((vm.nextcloud as NextcloudState.Error).message.contains("HTTPS"))
        assertTrue(login.startedFor.isEmpty())

        settings.allowCleartext = true
        vm.onIntent(SettingsIntent.StartLogin("http://192.168.1.5"))
        advanceUntilIdle()
        assertTrue(vm.nextcloud is NextcloudState.WaitingForBrowserAuth)
    }

    @Test
    fun `a cleartext login url from the server is refused and never opened`() = runTest {
        login.startResult = Result.success(LoginFlowSession("http://cloud/login/flow", "https://cloud/poll", "tok"))
        val vm = vm()
        vm.events.test {
            vm.onIntent(SettingsIntent.StartLogin("cloud.example"))
            advanceUntilIdle()
            expectNoEvents()
        }
        assertTrue((vm.nextcloud as NextcloudState.Error).message.contains("HTTPS"))
        vm.onIntent(SettingsIntent.BrowserLaunched)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(0, login.polls)
    }

    @Test
    fun `a non-web login url is refused even when cleartext is allowed`() = runTest {
        settings.allowCleartext = true
        login.startResult = Result.success(LoginFlowSession("intent://evil#Intent;end", "https://cloud/poll", "tok"))
        val vm = vm()
        vm.onIntent(SettingsIntent.StartLogin("cloud.example")); advanceUntilIdle()
        assertTrue(vm.nextcloud is NextcloudState.Error)
    }

    @Test
    fun `a server without login flow support is an error`() = runTest {
        login.startResult = Result.failure(LoginFlowException("Server does not support Login Flow v2"))
        val vm = vm()
        vm.onIntent(SettingsIntent.StartLogin("cloud.example"))
        advanceUntilIdle()
        assertEquals(NextcloudState.Error("Server does not support Login Flow v2"), vm.nextcloud)
    }

    @Test
    fun `after the browser opens the app polls until the user approves, then connects and syncs`() = runTest {
        login.pollResults = mutableListOf(Result.success(null), Result.success(null), Result.success(creds))
        val vm = vm()
        vm.onIntent(SettingsIntent.StartLogin("cloud.example"))
        advanceUntilIdle()

        vm.onIntent(SettingsIntent.BrowserLaunched)
        runCurrent()
        assertEquals(NextcloudState.Polling, vm.nextcloud)

        advanceTimeBy(2_000); runCurrent()
        assertEquals(NextcloudState.Polling, vm.nextcloud)

        advanceTimeBy(2_000); runCurrent()
        assertEquals(NextcloudState.Connected("alice", "https://cloud.example", null), vm.nextcloud)
        assertEquals(3, login.polls)
        assertEquals(NextcloudAccount("https://cloud.example", "alice", "app-pass"), settings.account)
        assertEquals(1, sync.requests)
    }

    @Test
    fun `returning to the app while polling checks for approval straight away`() = runTest {
        login.pollResults = mutableListOf(Result.success(null), Result.success(creds))
        val vm = vm()
        vm.onIntent(SettingsIntent.StartLogin("cloud.example")); advanceUntilIdle()
        vm.onIntent(SettingsIntent.BrowserLaunched); runCurrent()
        assertEquals(NextcloudState.Polling, vm.nextcloud)

        vm.onIntent(SettingsIntent.ReturnedToApp); runCurrent()

        assertEquals(NextcloudState.Connected("alice", "https://cloud.example", null), vm.nextcloud)
        assertEquals(2, login.polls)
    }

    @Test
    fun `returning to the app does nothing when no login is in progress`() = runTest {
        val vm = vm()
        vm.onIntent(SettingsIntent.ReturnedToApp); runCurrent()
        assertEquals(NextcloudState.Idle, vm.nextcloud)
        assertEquals(0, login.polls)
    }

    @Test
    fun `credentials naming a cleartext server are refused and not stored`() = runTest {
        login.pollResults = mutableListOf(Result.success(NextcloudCredentials("http://cloud.example", "alice", "app-pass")))
        val vm = vm()
        vm.onIntent(SettingsIntent.StartLogin("cloud.example")); advanceUntilIdle()
        vm.onIntent(SettingsIntent.BrowserLaunched); runCurrent()

        assertTrue((vm.nextcloud as NextcloudState.Error).message.contains("HTTPS"))
        assertNull(settings.account)
        assertEquals(0, sync.requests)
    }

    @Test
    fun `polling stops after connecting`() = runTest {
        login.pollResults = mutableListOf(Result.success(creds))
        val vm = vm()
        vm.onIntent(SettingsIntent.StartLogin("cloud.example")); advanceUntilIdle()
        vm.onIntent(SettingsIntent.BrowserLaunched); advanceTimeBy(60_000); runCurrent()
        assertEquals(1, login.polls)
    }

    @Test
    fun `cancelling returns to idle and stops polling`() = runTest {
        val vm = vm()
        vm.onIntent(SettingsIntent.StartLogin("cloud.example")); advanceUntilIdle()
        vm.onIntent(SettingsIntent.BrowserLaunched); advanceTimeBy(4_000); runCurrent()
        val pollsSoFar = login.polls

        vm.onIntent(SettingsIntent.CancelLogin)
        advanceTimeBy(60_000); runCurrent()

        assertEquals(NextcloudState.Idle, vm.nextcloud)
        assertEquals(pollsSoFar, login.polls)
        assertNull(settings.account)
    }

    @Test
    fun `polling gives up after twenty minutes`() = runTest {
        val vm = vm()
        vm.onIntent(SettingsIntent.StartLogin("cloud.example")); advanceUntilIdle()
        vm.onIntent(SettingsIntent.BrowserLaunched)
        advanceTimeBy(20 * 60_000L + 2_000); runCurrent()

        assertTrue((vm.nextcloud as NextcloudState.Error).message.contains("timed out", ignoreCase = true))
        assertNull(settings.account)
    }

    @Test
    fun `a few network blips while polling are tolerated`() = runTest {
        login.pollResults = mutableListOf(FakeLoginFlow.failure(), FakeLoginFlow.failure(), Result.success(creds))
        val vm = vm()
        vm.onIntent(SettingsIntent.StartLogin("cloud.example")); advanceUntilIdle()
        vm.onIntent(SettingsIntent.BrowserLaunched)
        advanceTimeBy(10_000); runCurrent()
        assertTrue(vm.nextcloud is NextcloudState.Connected)
    }

    @Test
    fun `losing the network while the browser is open does not abandon the login`() = runTest {
        val offline = Result.failure<NextcloudCredentials?>(LoginFlowException("Network error", java.io.IOException("offline")))
        login.pollResults = MutableList(30) { offline }.apply { add(Result.success(creds)) }
        val vm = vm()
        vm.onIntent(SettingsIntent.StartLogin("cloud.example")); advanceUntilIdle()
        vm.onIntent(SettingsIntent.BrowserLaunched)
        advanceTimeBy(70_000); runCurrent()
        assertTrue(vm.nextcloud is NextcloudState.Connected)
    }

    @Test
    fun `persistent polling failures end in an error`() = runTest {
        login.pollResults = mutableListOf(FakeLoginFlow.failure("connection refused"))
        val vm = vm()
        vm.onIntent(SettingsIntent.StartLogin("cloud.example")); advanceUntilIdle()
        vm.onIntent(SettingsIntent.BrowserLaunched)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(NextcloudState.Error("connection refused"), vm.nextcloud)
    }

    @Test
    fun `after an error the user can try again`() = runTest {
        login.startResult = Result.failure(LoginFlowException("down"))
        val vm = vm()
        vm.onIntent(SettingsIntent.StartLogin("cloud.example")); advanceUntilIdle()
        assertTrue(vm.nextcloud is NextcloudState.Error)

        login.startResult = Result.success(LoginFlowSession("https://cloud/login", "https://cloud/poll", "t"))
        vm.onIntent(SettingsIntent.StartLogin("cloud.example")); advanceUntilIdle()
        assertTrue(vm.nextcloud is NextcloudState.WaitingForBrowserAuth)
    }

    @Test
    fun `disconnecting forgets the account`() = runTest {
        settings.account = NextcloudAccount("https://cloud.example", "alice", "pw")
        val vm = vm(); runCurrent()
        vm.onIntent(SettingsIntent.Disconnect); runCurrent()
        assertEquals(NextcloudState.Idle, vm.nextcloud)
        assertNull(settings.account)
    }

    // --- sync --------------------------------------------------------------------------------

    @Test
    fun `sync now requests a sync only when connected`() = runTest {
        val vm = vm(); runCurrent()
        vm.onIntent(SettingsIntent.SyncNow); runCurrent()
        assertEquals(0, sync.requests)

        settings.account = NextcloudAccount("https://cloud.example", "alice", "pw")
        val connected = vm(); runCurrent()
        connected.onIntent(SettingsIntent.SyncNow); runCurrent()
        assertEquals(1, sync.requests)
    }

    @Test
    fun `the syncing flag and last sync time follow the background sync`() = runTest {
        settings.account = NextcloudAccount("https://cloud.example", "alice", "pw")
        val vm = vm(); runCurrent()
        assertFalse(vm.uiState.value.isSyncing)

        sync.running.value = true; runCurrent()
        assertTrue(vm.uiState.value.isSyncing)

        settings.recordSync(1_800_000_000_000); sync.running.value = false; runCurrent()
        assertEquals(NextcloudState.Connected("alice", "https://cloud.example", 1_800_000_000_000), vm.nextcloud)
        assertFalse(vm.uiState.value.isSyncing)
    }

    // --- PodcastIndex + preferences -----------------------------------------------------------

    @Test
    fun `loads saved podcastindex credentials`() = runTest {
        settings.podcastIndex = PodcastIndexCredentials("key", "secret")
        val vm = vm(); runCurrent()
        assertEquals(PodcastIndexForm("key", "secret", saved = true), vm.uiState.value.podcastIndex)
    }

    @Test
    fun `editing and saving podcastindex credentials stores them`() = runTest {
        val vm = vm(); runCurrent()
        vm.onIntent(SettingsIntent.EditPodcastIndexKey("k"))
        vm.onIntent(SettingsIntent.EditPodcastIndexSecret("s"))
        assertFalse(vm.uiState.value.podcastIndex.saved)

        vm.onIntent(SettingsIntent.SavePodcastIndex); runCurrent()

        assertEquals(PodcastIndexCredentials("k", "s"), settings.podcastIndex)
        assertTrue(vm.uiState.value.podcastIndex.saved)
    }

    @Test
    fun `incomplete podcastindex credentials are not saved`() = runTest {
        val vm = vm(); runCurrent()
        vm.onIntent(SettingsIntent.EditPodcastIndexKey("k"))
        vm.onIntent(SettingsIntent.SavePodcastIndex); runCurrent()
        assertNull(settings.podcastIndex)
        assertFalse(vm.uiState.value.podcastIndex.saved)
    }

    @Test
    fun `clearing podcastindex credentials removes them`() = runTest {
        settings.podcastIndex = PodcastIndexCredentials("key", "secret")
        val vm = vm(); runCurrent()
        vm.onIntent(SettingsIntent.ClearPodcastIndex); runCurrent()
        assertNull(settings.podcastIndex)
        assertEquals(PodcastIndexForm("", "", saved = false), vm.uiState.value.podcastIndex)
    }

    @Test
    fun `download preferences are read and written through`() = runTest {
        val vm = vm(); runCurrent()
        assertTrue(vm.uiState.value.downloads.unmeteredOnly)
        assertTrue(vm.uiState.value.downloads.deleteWhenPlayed)

        vm.onIntent(SettingsIntent.SetUnmeteredOnly(false))
        vm.onIntent(SettingsIntent.SetDeleteWhenPlayed(false))
        runCurrent()

        assertFalse(settings.autoDownloadUnmeteredOnly)
        assertFalse(settings.deleteDownloadsWhenPlayed)
        assertFalse(vm.uiState.value.downloads.unmeteredOnly)
        assertFalse(vm.uiState.value.downloads.deleteWhenPlayed)
    }

    @Test
    fun `shows the stored schedule, auto-download and retention preferences`() = runTest {
        settings.syncIntervalHours = 12
        settings.autoDownloadChargingOnly = true
        settings.autoDownloadPerPodcast = 5
        settings.keepPerPodcast = 10
        settings.maxStorageBytes = 2_000_000_000
        val vm = vm(); runCurrent()

        val state = vm.uiState.value
        assertEquals(12L, state.syncIntervalHours)
        assertEquals(DownloadPrefs(true, true, chargingOnly = true, autoDownloadPerPodcast = 5, keepPerPodcast = 10, maxStorageBytes = 2_000_000_000), state.downloads)
    }

    @Test
    fun `download preferences are saved and reflected`() = runTest {
        val vm = vm(); runCurrent()

        vm.onIntent(SettingsIntent.SetChargingOnly(true))
        vm.onIntent(SettingsIntent.SetAutoDownloadCount(2))
        vm.onIntent(SettingsIntent.SetKeepPerPodcast(5))
        vm.onIntent(SettingsIntent.SetMaxStorage(5_000_000_000))
        runCurrent()

        assertTrue(settings.autoDownloadChargingOnly)
        assertEquals(2, settings.autoDownloadPerPodcast)
        assertEquals(5, settings.keepPerPodcast)
        assertEquals(5_000_000_000, settings.maxStorageBytes)
        assertEquals(
            DownloadPrefs(true, true, chargingOnly = true, autoDownloadPerPodcast = 2, keepPerPodcast = 5, maxStorageBytes = 5_000_000_000),
            vm.uiState.value.downloads,
        )
    }

    @Test
    fun `nonsense download limits are clamped`() = runTest {
        val vm = vm(); runCurrent()

        vm.onIntent(SettingsIntent.SetAutoDownloadCount(0))
        vm.onIntent(SettingsIntent.SetKeepPerPodcast(-3))
        vm.onIntent(SettingsIntent.SetMaxStorage(-1))
        runCurrent()

        assertEquals(1, vm.uiState.value.downloads.autoDownloadPerPodcast)
        assertEquals(0, vm.uiState.value.downloads.keepPerPodcast)
        assertEquals(0L, vm.uiState.value.downloads.maxStorageBytes)
    }

    @Test
    fun `changing the sync interval is saved and reschedules immediately`() = runTest {
        val vm = vm(); runCurrent()

        vm.onIntent(SettingsIntent.SetSyncInterval(3)); runCurrent()
        vm.onIntent(SettingsIntent.SetSyncInterval(0)); runCurrent()

        assertEquals(0L, settings.syncIntervalHours)
        assertEquals(0L, vm.uiState.value.syncIntervalHours)
        assertEquals(listOf(3L, 0L), sync.intervals)
    }

    @Test
    fun `a negative sync interval turns the schedule off`() = runTest {
        val vm = vm(); runCurrent()

        vm.onIntent(SettingsIntent.SetSyncInterval(-4)); runCurrent()

        assertEquals(listOf(0L), sync.intervals)
    }

    @Test
    fun `allowing cleartext is saved and flagged as needing a restart`() = runTest {
        val vm = vm(); runCurrent()
        assertFalse(vm.uiState.value.restartRequired)
        vm.onIntent(SettingsIntent.SetAllowCleartext(true)); runCurrent()
        assertTrue(settings.allowCleartext)
        assertTrue(vm.uiState.value.restartRequired)
    }
}
