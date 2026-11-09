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
    /** False if the app password could not be stored securely; nothing is saved then. */
    fun saveNextcloudAccount(account: NextcloudAccount): Boolean
    fun clearNextcloudAccount()

    suspend fun podcastIndexCredentials(): PodcastIndexCredentials?
    /** False if the credentials could not be stored securely; passing null (or incomplete ones) removes them. */
    fun savePodcastIndexCredentials(credentials: PodcastIndexCredentials?): Boolean

    var autoDownloadUnmeteredOnly: Boolean
    var deleteDownloadsWhenPlayed: Boolean
    var allowCleartext: Boolean
    /** Shows developer-only actions (debug builds only), such as dropping a podcast's newest episode. */
    var debugTools: Boolean
    /** Discover leaves out shows their directory marks explicit. */
    var hideExplicit: Boolean
    /** Name of the Episodes segment the user last chose; null until they choose one. */
    var episodesSegment: String?
    /** Whether sharing an episode adds a link to it (or its show). */
    var shareLink: Boolean

    /** Only while charging; background auto-downloads only. */
    var autoDownloadChargingOnly: Boolean
    /** How many new episodes of one podcast an automatic download pass may fetch. */
    var autoDownloadPerPodcast: Int
    /** Newest downloads kept per podcast; 0 means unlimited. */
    var keepPerPodcast: Int
    /** 0 means unlimited. */
    var maxStorageBytes: Long
    /** Hours between background syncs; 0 turns the schedule off. */
    var syncIntervalHours: Long
}
