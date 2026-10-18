// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.settings

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.sonora.core.download.DownloadPolicy
import org.sonora.core.network.discovery.DiscoveryCredentialsSource
import org.sonora.core.network.discovery.PodcastIndexCredentials
import org.sonora.core.security.SecretStore
import org.sonora.ui.theme.ThemeMode
import org.sonora.ui.theme.ThemePreferenceStore
import org.sonora.ui.theme.ThemePreferences

data class NextcloudAccount(val serverUrl: String, val loginName: String, val appPassword: String)

/** User preferences. Secrets go to [SecretStore]; everything else to plain SharedPreferences. */
class AppSettings(context: Context, private val secrets: SecretStore) : DiscoveryCredentialsSource, SettingsStore, ThemePreferenceStore {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Cleartext is off unless the user opts in for local development. Read when networking is set up. */
    override var allowCleartext: Boolean
        get() = prefs.getBoolean(ALLOW_CLEARTEXT, false)
        set(value) = prefs.edit().putBoolean(ALLOW_CLEARTEXT, value).apply()

    override var autoDownloadUnmeteredOnly: Boolean
        get() = prefs.getBoolean(UNMETERED_ONLY, true)
        set(value) = prefs.edit().putBoolean(UNMETERED_ONLY, value).apply()

    override var deleteDownloadsWhenPlayed: Boolean
        get() = prefs.getBoolean(DELETE_PLAYED, true)
        set(value) = prefs.edit().putBoolean(DELETE_PLAYED, value).apply()

    /** 0 means unlimited. */
    var maxStorageBytes: Long
        get() = prefs.getLong(MAX_STORAGE, 0)
        set(value) = prefs.edit().putLong(MAX_STORAGE, value).apply()

    /** Newest downloads kept per podcast; 0 means unlimited. */
    var keepPerPodcast: Int
        get() = prefs.getInt(KEEP_PER_PODCAST, 0)
        set(value) = prefs.edit().putInt(KEEP_PER_PODCAST, value.coerceAtLeast(0)).apply()

    var syncIntervalHours: Long
        get() = prefs.getLong(SYNC_INTERVAL, 6)
        set(value) = prefs.edit().putLong(SYNC_INTERVAL, value.coerceAtLeast(1)).apply()

    private val _lastSyncMillis = MutableStateFlow(prefs.getLong(LAST_SYNC, 0))
    override val lastSyncMillis: StateFlow<Long> = _lastSyncMillis

    override fun recordSync(epochMillis: Long) {
        prefs.edit().putLong(LAST_SYNC, epochMillis).apply()
        _lastSyncMillis.value = epochMillis
    }

    private val _theme = MutableStateFlow(
        ThemePreferences(
            mode = runCatching { ThemeMode.valueOf(prefs.getString(THEME_MODE, null).orEmpty()) }.getOrDefault(ThemeMode.SYSTEM),
            artworkColors = prefs.getBoolean(ARTWORK_COLORS, true),
        ),
    )
    override val preferences: Flow<ThemePreferences> = _theme

    override suspend fun save(preferences: ThemePreferences) {
        prefs.edit().putString(THEME_MODE, preferences.mode.name).putBoolean(ARTWORK_COLORS, preferences.artworkColors).apply()
        _theme.value = preferences
    }

    fun downloadPolicy() = DownloadPolicy(
        deleteWhenPlayed = deleteDownloadsWhenPlayed,
        keepPerPodcast = keepPerPodcast,
        maxStorageBytes = maxStorageBytes,
    )

    override fun nextcloudAccount(): NextcloudAccount? {
        val server = prefs.getString(NC_SERVER, null) ?: return null
        val login = prefs.getString(NC_LOGIN, null) ?: return null
        val password = secrets.get(NC_PASSWORD) ?: return null
        return NextcloudAccount(server, login, password)
    }

    override fun saveNextcloudAccount(account: NextcloudAccount) {
        prefs.edit().putString(NC_SERVER, account.serverUrl).putString(NC_LOGIN, account.loginName).apply()
        secrets.put(NC_PASSWORD, account.appPassword)
    }

    override fun clearNextcloudAccount() {
        prefs.edit().remove(NC_SERVER).remove(NC_LOGIN).apply()
        secrets.remove(NC_PASSWORD)
    }

    override fun savePodcastIndexCredentials(credentials: PodcastIndexCredentials?) {
        if (credentials == null || !credentials.isComplete) {
            secrets.remove(PI_KEY)
            secrets.remove(PI_SECRET)
        } else {
            secrets.put(PI_KEY, credentials.apiKey)
            secrets.put(PI_SECRET, credentials.apiSecret)
        }
    }

    override suspend fun podcastIndexCredentials(): PodcastIndexCredentials? {
        val key = secrets.get(PI_KEY) ?: return null
        val secret = secrets.get(PI_SECRET) ?: return null
        return PodcastIndexCredentials(key, secret)
    }

    private companion object {
        const val ALLOW_CLEARTEXT = "allow_cleartext"
        const val UNMETERED_ONLY = "auto_download_unmetered_only"
        const val DELETE_PLAYED = "delete_downloads_when_played"
        const val MAX_STORAGE = "max_storage_bytes"
        const val KEEP_PER_PODCAST = "keep_per_podcast"
        const val THEME_MODE = "theme_mode"
        const val ARTWORK_COLORS = "artwork_colors"
        const val LAST_SYNC = "last_sync_millis"
        const val SYNC_INTERVAL = "sync_interval_hours"
        const val NC_SERVER = "nextcloud_server"
        const val NC_LOGIN = "nextcloud_login"
        const val NC_PASSWORD = "nextcloud_app_password"
        const val PI_KEY = "podcastindex_key"
        const val PI_SECRET = "podcastindex_secret"
    }
}
