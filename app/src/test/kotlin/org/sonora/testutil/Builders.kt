// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.testutil

import org.sonora.core.database.EpisodeEntity
import org.sonora.core.database.PodcastDao
import org.sonora.core.database.PodcastEntity
import org.sonora.core.model.DownloadState

const val SHOW_FEED_URL = "https://f/feed"

/** The id of the default show ("Show" at [SHOW_FEED_URL]), inserting it the first time it is asked for. */
suspend fun PodcastDao.showId(): Long =
    findByFeedUrl(SHOW_FEED_URL)?.id ?: insert(PodcastEntity(feedUrl = SHOW_FEED_URL, title = "Show"))

/** An episode whose enclosure is `https://cdn/<guid>.mp3` unless said otherwise. */
@Suppress("LongParameterList")
fun episodeEntity(
    podcastId: Long,
    guid: String,
    title: String = guid,
    enclosureUrl: String = "https://cdn/$guid.mp3",
    durationSeconds: Long? = null,
    publishedAtMillis: Long? = null,
    playbackPositionSeconds: Long = 0,
    played: Boolean = false,
    downloadState: DownloadState = DownloadState.NONE,
    downloadPath: String? = null,
    sizeBytes: Long? = null,
) = EpisodeEntity(
    podcastId = podcastId, guid = guid, title = title, enclosureUrl = enclosureUrl,
    durationSeconds = durationSeconds, publishedAtMillis = publishedAtMillis,
    playbackPositionSeconds = playbackPositionSeconds, played = played,
    downloadState = downloadState, downloadPath = downloadPath, sizeBytes = sizeBytes,
)
