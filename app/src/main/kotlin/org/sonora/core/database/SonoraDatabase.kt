// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        PodcastEntity::class,
        EpisodeEntity::class,
        SyncQueueActionEntity::class,
        SyncStateEntity::class,
        SubscriptionChangeEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class SonoraDatabase : RoomDatabase() {
    abstract fun podcastDao(): PodcastDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun syncQueueDao(): SyncQueueDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun subscriptionChangeDao(): SubscriptionChangeDao
}
