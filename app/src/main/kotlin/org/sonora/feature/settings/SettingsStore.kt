// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.settings

import kotlinx.coroutines.flow.StateFlow
import org.sonora.core.network.discovery.PodcastIndexCredentials

/** What the settings screen reads and writes; [AppSettings] is the real implementation. */
interface SettingsStore {
    /** Epoch millis of the last successful sync; 0 when never. */
    val lastSyncMillis: StateFlow<Long>
    fun recordSync(epochMillis: Long)

    fun nextcloudAccount(): NextcloudAccount?
    fun saveNextcloudAccount(account: NextcloudAccount)
    fun clearNextcloudAccount()

    suspend fun podcastIndexCredentials(): PodcastIndexCredentials?
    fun savePodcastIndexCredentials(credentials: PodcastIndexCredentials?)

    var autoDownloadUnmeteredOnly: Boolean
    var deleteDownloadsWhenPlayed: Boolean
    var allowCleartext: Boolean
}
