// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import org.sonora.core.model.SyncActionType

@Dao
interface PodcastDao {
    @Insert
    suspend fun insert(podcast: PodcastEntity): Long

    @Update
    suspend fun update(podcast: PodcastEntity)

    @Query("DELETE FROM podcast WHERE feedUrl = :feedUrl")
    suspend fun deleteByFeedUrl(feedUrl: String)

    @Query("SELECT * FROM podcast WHERE feedUrl = :feedUrl")
    suspend fun findByFeedUrl(feedUrl: String): PodcastEntity?

    @Query("SELECT * FROM podcast WHERE id = :id")
    suspend fun findById(id: Long): PodcastEntity?

    @Query("SELECT * FROM podcast ORDER BY title COLLATE NOCASE")
    fun observeAll(): Flow<List<PodcastEntity>>

    @Query("SELECT * FROM podcast")
    suspend fun getAll(): List<PodcastEntity>
}

@Dao
interface EpisodeDao {
    @Insert
    suspend fun insertAll(episodes: List<EpisodeEntity>)

    @Update
    suspend fun update(episode: EpisodeEntity)

    @Update
    suspend fun updateAll(episodes: List<EpisodeEntity>)

    @Query("SELECT * FROM episode WHERE id = :id")
    suspend fun findById(id: Long): EpisodeEntity?

    @Query("SELECT * FROM episode WHERE enclosureUrl = :enclosureUrl LIMIT 1")
    suspend fun findByEnclosureUrl(enclosureUrl: String): EpisodeEntity?

    @Query("SELECT * FROM episode WHERE podcastId = :podcastId")
    suspend fun getForPodcast(podcastId: Long): List<EpisodeEntity>

    @Query("SELECT * FROM episode WHERE podcastId = :podcastId ORDER BY publishedAtMillis DESC")
    fun observeForPodcast(podcastId: Long): Flow<List<EpisodeEntity>>
}

@Dao
interface SyncQueueDao {
    @Insert
    suspend fun insert(action: SyncQueueActionEntity): Long

    @Query("SELECT * FROM sync_queue_action ORDER BY timestampUtcMillis, id")
    suspend fun getAll(): List<SyncQueueActionEntity>

    @Query("DELETE FROM sync_queue_action WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM sync_queue_action WHERE type = :type AND episodeUrl = :episodeUrl")
    suspend fun deleteByTypeAndEpisode(type: SyncActionType, episodeUrl: String)

    @Query("SELECT COUNT(*) FROM sync_queue_action")
    fun observeCount(): Flow<Int>
}

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE id = 1")
    suspend fun get(): SyncStateEntity?

    @androidx.room.Upsert
    suspend fun upsert(state: SyncStateEntity)
}

@Dao
interface SubscriptionChangeDao {
    @androidx.room.Upsert
    suspend fun upsert(change: SubscriptionChangeEntity)

    @Query("SELECT * FROM subscription_change")
    suspend fun getAll(): List<SubscriptionChangeEntity>

    @Query("DELETE FROM subscription_change WHERE feedUrl IN (:feedUrls)")
    suspend fun deleteByFeedUrls(feedUrls: List<String>)
}
