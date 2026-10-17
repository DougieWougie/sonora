// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import org.sonora.core.model.DownloadState
import org.sonora.core.model.SyncActionType

@Entity(tableName = "podcast", indices = [Index(value = ["feedUrl"], unique = true)])
data class PodcastEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val feedUrl: String,
    val title: String,
    val description: String? = null,
    val link: String? = null,
    val author: String? = null,
    val imageUrl: String? = null,
    val lastRefreshedAtMillis: Long = 0,
    val autoDownload: Boolean = false,
)

@Entity(
    tableName = "episode",
    foreignKeys = [
        ForeignKey(
            entity = PodcastEntity::class,
            parentColumns = ["id"],
            childColumns = ["podcastId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["podcastId", "guid"], unique = true),
        Index(value = ["enclosureUrl"]),
    ],
)
data class EpisodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val podcastId: Long,
    val guid: String,
    val title: String,
    val description: String? = null,
    val enclosureUrl: String,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val durationSeconds: Long? = null,
    val publishedAtMillis: Long? = null,
    val imageUrl: String? = null,
    val playbackPositionSeconds: Long = 0,
    val played: Boolean = false,
    val downloadState: DownloadState = DownloadState.NONE,
    val downloadPath: String? = null,
    val downloadedBytes: Long = 0,
)

/** A local change that has not yet been pushed to the sync server. Timestamps are UTC epoch millis. */
@Entity(tableName = "sync_queue_action")
data class SyncQueueActionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: SyncActionType,
    val feedUrl: String,
    val episodeUrl: String,
    val guid: String? = null,
    val timestampUtcMillis: Long,
    val startedSeconds: Long? = null,
    val positionSeconds: Long? = null,
    val totalSeconds: Long? = null,
)

/** Singleton row (id = 1) holding the "since" cursors of the last successful sync. */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = 1,
    val subscriptionsSince: Long = 0,
    val episodeActionsSince: Long = 0,
)

/** A local subscribe/unsubscribe not yet pushed to the server. One row per feed: the latest intent wins. */
@Entity(tableName = "subscription_change")
data class SubscriptionChangeEntity(
    @PrimaryKey val feedUrl: String,
    val subscribed: Boolean,
    val timestampUtcMillis: Long,
)
