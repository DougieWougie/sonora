// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.download

/** The per-episode actions the UI offers; implementations decide how (WorkManager, repositories, ...). */
interface EpisodeActions {
    suspend fun download(episodeId: Long)
    suspend fun deleteDownload(episodeId: Long)
    suspend fun markPlayed(episodeId: Long)
    suspend fun markUnplayed(episodeId: Long)
    suspend fun countOlderUnplayed(episodeId: Long): Int
    suspend fun markOlderPlayed(episodeId: Long): Int
}
