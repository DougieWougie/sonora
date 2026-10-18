// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.database

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.sonora.core.model.SyncActionType

class MutableClock(var nowMillis: Long = 1_700_000_000_000) : Clock() {
    override fun getZone() = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId?) = this
    override fun instant(): Instant = Instant.ofEpochMilli(nowMillis)
}

class FakeTransactionRunner : TransactionRunner {
    var runs = 0
    override suspend fun <T> run(block: suspend () -> T): T {
        runs++
        return block()
    }
}

class FakePodcastDao : PodcastDao {
    val rows = MutableStateFlow<List<PodcastEntity>>(emptyList())
    private var nextId = 1L

    override suspend fun insert(podcast: PodcastEntity): Long {
        val id = nextId++
        rows.value += podcast.copy(id = id)
        return id
    }

    override suspend fun update(podcast: PodcastEntity) {
        rows.value = rows.value.map { if (it.id == podcast.id) podcast else it }
    }

    override suspend fun deleteByFeedUrl(feedUrl: String) {
        rows.value = rows.value.filterNot { it.feedUrl == feedUrl }
    }

    override suspend fun findByFeedUrl(feedUrl: String) = rows.value.firstOrNull { it.feedUrl == feedUrl }
    override suspend fun findById(id: Long) = rows.value.firstOrNull { it.id == id }
    override fun observeAll(): Flow<List<PodcastEntity>> = rows
    override fun observeById(id: Long): Flow<PodcastEntity?> = rows.map { all -> all.firstOrNull { it.id == id } }
    override suspend fun getAll() = rows.value
}

class FakeEpisodeDao : EpisodeDao {
    val rows = MutableStateFlow<List<EpisodeEntity>>(emptyList())
    private var nextId = 1L

    override suspend fun insertAll(episodes: List<EpisodeEntity>) {
        rows.value += episodes.map { it.copy(id = nextId++) }
    }

    override suspend fun update(episode: EpisodeEntity) {
        rows.value = rows.value.map { if (it.id == episode.id) episode else it }
    }

    override suspend fun updateAll(episodes: List<EpisodeEntity>) = episodes.forEach { update(it) }
    override suspend fun findById(id: Long) = rows.value.firstOrNull { it.id == id }
    override suspend fun findByEnclosureUrl(enclosureUrl: String) = rows.value.firstOrNull { it.enclosureUrl == enclosureUrl }
    override suspend fun getForPodcast(podcastId: Long) = rows.value.filter { it.podcastId == podcastId }
    override suspend fun getByDownloadState(state: org.sonora.core.model.DownloadState) =
        rows.value.filter { it.downloadState == state }
    override suspend fun getOlderUnplayed(podcastId: Long, publishedBefore: Long) =
        rows.value.filter { it.podcastId == podcastId && !it.played && (it.publishedAtMillis ?: Long.MAX_VALUE) < publishedBefore }
    override fun observeForPodcast(podcastId: Long): Flow<List<EpisodeEntity>> =
        rows.map { all -> all.filter { it.podcastId == podcastId }.sortedByDescending { it.publishedAtMillis } }

    override fun observeStats(): Flow<List<PodcastEpisodeStats>> = rows.map { all ->
        all.groupBy { it.podcastId }.map { (id, eps) ->
            PodcastEpisodeStats(id, eps.count { !it.played }, eps.mapNotNull { it.publishedAtMillis }.maxOrNull())
        }
    }

    override fun observeByDownloadStates(states: List<org.sonora.core.model.DownloadState>): Flow<List<EpisodeEntity>> =
        rows.map { all -> all.filter { it.downloadState in states } }

    override fun observeUnplayed(limit: Int): Flow<List<EpisodeEntity>> =
        rows.map { all -> all.filter { !it.played }.sortedByDescending { it.publishedAtMillis }.take(limit) }
}

class FakeSyncQueueDao : SyncQueueDao {
    val rows = MutableStateFlow<List<SyncQueueActionEntity>>(emptyList())
    private var nextId = 1L

    override suspend fun insert(action: SyncQueueActionEntity): Long {
        val id = nextId++
        rows.value += action.copy(id = id)
        return id
    }

    override suspend fun getAll() = rows.value.sortedWith(compareBy({ it.timestampUtcMillis }, { it.id }))

    override suspend fun deleteByIds(ids: List<Long>) {
        rows.value = rows.value.filterNot { it.id in ids }
    }

    override suspend fun deleteByTypeAndEpisode(type: SyncActionType, episodeUrl: String) {
        rows.value = rows.value.filterNot { it.type == type && it.episodeUrl == episodeUrl }
    }

    override fun observeCount(): Flow<Int> = rows.map { it.size }
}

class FakeSyncStateDao : SyncStateDao {
    var state: SyncStateEntity? = null
    override suspend fun get() = state
    override suspend fun upsert(state: SyncStateEntity) {
        this.state = state
    }
}

class FakeSubscriptionChangeDao : SubscriptionChangeDao {
    val rows = mutableMapOf<String, SubscriptionChangeEntity>()
    override suspend fun upsert(change: SubscriptionChangeEntity) {
        rows[change.feedUrl] = change
    }

    override suspend fun getAll() = rows.values.toList()
    override suspend fun deleteByFeedUrls(feedUrls: List<String>) {
        feedUrls.forEach { rows.remove(it) }
    }
}
