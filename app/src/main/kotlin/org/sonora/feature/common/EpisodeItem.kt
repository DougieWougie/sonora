// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.common

import org.sonora.core.database.EpisodeEntity
import org.sonora.core.database.PodcastEntity
import org.sonora.core.model.DownloadState

/** An episode as the UI shows it, with the context of its podcast and the queue. */
data class EpisodeItem(
    val id: Long,
    val podcastId: Long,
    val podcastTitle: String?,
    val title: String,
    val publishedAtMillis: Long?,
    val durationSeconds: Long?,
    val positionSeconds: Long,
    val played: Boolean,
    val downloadState: DownloadState,
    val downloadedBytes: Long,
    val sizeBytes: Long?,
    val artworkUrl: String?,
    val queued: Boolean,
)

fun EpisodeEntity.toItem(podcast: PodcastEntity?, queued: Boolean) = EpisodeItem(
    id = id,
    podcastId = podcastId,
    podcastTitle = podcast?.title,
    title = title,
    publishedAtMillis = publishedAtMillis,
    durationSeconds = durationSeconds,
    positionSeconds = playbackPositionSeconds,
    played = played,
    downloadState = downloadState,
    downloadedBytes = downloadedBytes,
    sizeBytes = sizeBytes,
    artworkUrl = imageUrl ?: podcast?.imageUrl,
    queued = queued,
)
