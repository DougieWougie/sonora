// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.sonora.core.download.DownloadPolicy
import org.sonora.core.network.discovery.PodcastIndexCredentials
import org.sonora.testutil.FakeSecretStore
import org.sonora.ui.theme.ThemeMode
import org.sonora.ui.theme.ThemePreferences

@RunWith(AndroidJUnit4::class)
class AppSettingsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val secrets = FakeSecretStore()
    private val prefs get() = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private fun settings() = AppSettings(context, secrets)

    @Before
    fun clear() {
        prefs.edit().clear().commit()
    }

    // --- defaults and persistence --------------------------------------------------------------

    @Test
    fun `defaults are safe`() {
        val s = settings()

        assertFalse(s.allowCleartext)
        assertTrue(s.autoDownloadUnmeteredOnly)
        assertTrue(s.deleteDownloadsWhenPlayed)
        assertTrue(s.hideExplicit)
        assertFalse(s.autoDownloadChargingOnly)
        assertEquals(3, s.autoDownloadPerPodcast)
        assertEquals(0L, s.maxStorageBytes)
        assertEquals(0, s.keepPerPodcast)
        assertEquals(6L, s.syncIntervalHours)
        assertEquals(0L, s.lastSyncMillis.value)
    }

    @Test
    fun `every setting survives a restart`() {
        settings().apply {
            allowCleartext = true
            autoDownloadUnmeteredOnly = false
            deleteDownloadsWhenPlayed = false
            hideExplicit = false
            autoDownloadChargingOnly = true
            autoDownloadPerPodcast = 7
            maxStorageBytes = 5_000_000_000
            keepPerPodcast = 4
            syncIntervalHours = 12
            recordSync(1_700_000_000_000)
        }

        val s = settings()
        assertTrue(s.allowCleartext)
        assertFalse(s.autoDownloadUnmeteredOnly)
        assertFalse(s.deleteDownloadsWhenPlayed)
        assertFalse(s.hideExplicit)
        assertTrue(s.autoDownloadChargingOnly)
        assertEquals(7, s.autoDownloadPerPodcast)
        assertEquals(5_000_000_000, s.maxStorageBytes)
        assertEquals(4, s.keepPerPodcast)
        assertEquals(12L, s.syncIntervalHours)
        assertEquals(1_700_000_000_000, s.lastSyncMillis.value)
    }

    @Test
    fun `out-of-range values are clamped`() {
        val s = settings()

        s.autoDownloadPerPodcast = 0
        s.keepPerPodcast = -3
        s.syncIntervalHours = -1

        assertEquals(1, s.autoDownloadPerPodcast)
        assertEquals(0, s.keepPerPodcast)
        assertEquals(0L, s.syncIntervalHours)
    }

    @Test
    fun `recording a sync publishes the time`() {
        val s = settings()

        s.recordSync(42)

        assertEquals(42L, s.lastSyncMillis.value)
    }

    @Test
    fun `the download policy reflects the retention settings`() {
        val s = settings()
        s.deleteDownloadsWhenPlayed = false
        s.keepPerPodcast = 5
        s.maxStorageBytes = 1_000

        assertEquals(DownloadPolicy(deleteWhenPlayed = false, keepPerPodcast = 5, maxStorageBytes = 1_000), s.downloadPolicy())
    }

    // --- theme ---------------------------------------------------------------------------------

    @Test
    fun `theme defaults to the system mode with artwork colours`() = runBlocking {
        assertEquals(ThemePreferences(ThemeMode.SYSTEM, artworkColors = true), settings().preferences.first())
    }

    @Test
    fun `a saved theme is published and survives a restart`() = runBlocking {
        val s = settings()
        val chosen = ThemePreferences(ThemeMode.AMOLED, artworkColors = false)

        s.save(chosen)

        assertEquals(chosen, s.preferences.first())
        assertEquals(chosen, settings().preferences.first())
    }

    @Test
    fun `an unrecognised stored theme mode falls back to the system mode`() = runBlocking {
        prefs.edit().putString("theme_mode", "NEON").commit()

        assertEquals(ThemeMode.SYSTEM, settings().preferences.first().mode)
    }

    // --- Nextcloud account ---------------------------------------------------------------------

    private val account = NextcloudAccount("https://cloud.example", "alice", "app-pass")

    @Test
    fun `there is no account until one is saved`() {
        assertNull(settings().nextcloudAccount())
    }

    @Test
    fun `the app password goes to the secret store, never to preferences`() {
        val s = settings()

        s.saveNextcloudAccount(account)

        assertEquals(account, s.nextcloudAccount())
        assertEquals("app-pass", secrets.values.values.single())
        assertFalse(prefs.all.values.any { it == "app-pass" })
    }

    @Test
    fun `an account missing its password is treated as absent`() {
        val s = settings()
        s.saveNextcloudAccount(account)
        secrets.values.clear()

        assertNull(s.nextcloudAccount())
    }

    @Test
    fun `clearing the account removes the server, login and password`() {
        val s = settings()
        s.saveNextcloudAccount(account)

        s.clearNextcloudAccount()

        assertNull(settings().nextcloudAccount())
        assertTrue(secrets.values.isEmpty())
        assertFalse(prefs.contains("nextcloud_server"))
        assertFalse(prefs.contains("nextcloud_login"))
    }

    // --- PodcastIndex credentials --------------------------------------------------------------

    @Test
    fun `PodcastIndex credentials round trip through the secret store`() = runBlocking {
        val s = settings()
        assertNull(s.podcastIndexCredentials())

        s.savePodcastIndexCredentials(PodcastIndexCredentials("KEY", "SECRET"))

        assertEquals(PodcastIndexCredentials("KEY", "SECRET"), s.podcastIndexCredentials())
        assertTrue(prefs.all.isEmpty())
    }

    @Test
    fun `saving null or incomplete credentials removes them`() = runBlocking {
        val s = settings()

        s.savePodcastIndexCredentials(PodcastIndexCredentials("KEY", "SECRET"))
        s.savePodcastIndexCredentials(null)
        assertNull(s.podcastIndexCredentials())
        assertTrue(secrets.values.isEmpty())

        s.savePodcastIndexCredentials(PodcastIndexCredentials("KEY", "SECRET"))
        s.savePodcastIndexCredentials(PodcastIndexCredentials("KEY", ""))
        assertNull(s.podcastIndexCredentials())
        assertTrue(secrets.values.isEmpty())
    }
}
