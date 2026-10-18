// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.download

/** The per-episode actions the UI offers; implementations decide how (WorkManager, repositories, ...). */
interface EpisodeActions {
    suspend fun download(episodeId: Long)
    suspend fun deleteDownload(episodeId: Long)
    /** Stops a queued or running download and discards what was fetched so far. */
    suspend fun cancelDownload(episodeId: Long)
    /** Returns how many downloads were removed. */
    suspend fun deleteAllPlayedDownloads(): Int
    suspend fun markPlayed(episodeId: Long)
    suspend fun markUnplayed(episodeId: Long)
}
