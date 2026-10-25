// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.database

import org.sonora.testutil.episodeEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.sonora.core.model.DownloadState
import org.sonora.core.model.SyncActionType

/**
 * The behaviour the repositories rely on from the DAOs. Run against the in-memory fakes and against real
 * Room/SQLite, so a fake cannot drift from the queries it stands in for.
 */
abstract class DaoContract {
    protected abstract val podcasts: PodcastDao
    protected abstract val episodes: EpisodeDao
    protected abstract val queue: SyncQueueDao
    protected abstract val syncState: SyncStateDao
    protected abstract val changes: SubscriptionChangeDao

    private suspend fun podcast(feedUrl: String, title: String = feedUrl) =
        podcasts.insert(PodcastEntity(feedUrl = feedUrl, title = title))

    private fun episode(
        podcastId: Long,
        guid: String,
        published: Long? = null,
        played: Boolean = false,
        state: DownloadState = DownloadState.NONE,
    ) = episodeEntity(podcastId, guid, publishedAtMillis = published, played = played, downloadState = state)

    private fun action(type: SyncActionType, episodeUrl: String, at: Long) =
        SyncQueueActionEntity(type = type, feedUrl = "https://f", episodeUrl = episodeUrl, timestampUtcMillis = at)

    // --- podcasts ------------------------------------------------------------------------------

    @Test
    fun `podcasts are found by id and by feed url`() = runBlocking {
        val id = podcast("https://a/feed", "A")

        assertEquals("A", podcasts.findById(id)!!.title)
        assertEquals(id, podcasts.findByFeedUrl("https://a/feed")!!.id)
        assertNull(podcasts.findByFeedUrl("https://missing"))
        assertNull(podcasts.findById(id + 1))
    }

    @Test
    fun `podcasts are listed by title ignoring case`() = runBlocking {
        podcast("https://1", "banana")
        podcast("https://2", "Apple")
        podcast("https://3", "cherry")

        assertEquals(listOf("Apple", "banana", "cherry"), podcasts.observeAll().first().map { it.title })
    }

    @Test
    fun `updating a podcast replaces its row`() = runBlocking {
        val id = podcast("https://a", "Old")

        podcasts.update(podcasts.findById(id)!!.copy(title = "New", autoDownload = true))

        val row = podcasts.findById(id)!!
        assertEquals("New", row.title)
        assertEquals(true, row.autoDownload)
        assertEquals("New", podcasts.observeById(id).first()!!.title)
    }

    @Test
    fun `deleting a podcast by feed url removes it and its episodes`() = runBlocking {
        val keep = podcast("https://keep")
        val drop = podcast("https://drop")
        episodes.insertAll(listOf(episode(keep, "k"), episode(drop, "d")))

        podcasts.deleteByFeedUrl("https://drop")

        assertEquals(listOf("https://keep"), podcasts.getAll().map { it.feedUrl })
        assertNull(podcasts.observeById(drop).first())
        assertEquals(emptyList<EpisodeEntity>(), episodes.getForPodcast(drop))
        assertEquals(1, episodes.getForPodcast(keep).size)
    }

    // --- episodes ------------------------------------------------------------------------------

    @Test
    fun `episodes are found by id, enclosure url and podcast`() = runBlocking {
        val a = podcast("https://a")
        val b = podcast("https://b")
        episodes.insertAll(listOf(episode(a, "a1"), episode(a, "a2"), episode(b, "b1")))

        val a1 = episodes.getForPodcast(a).first { it.guid == "a1" }
        assertEquals("a1", episodes.findById(a1.id)!!.guid)
        assertEquals("a2", episodes.findByEnclosureUrl("https://cdn/a2.mp3")!!.guid)
        assertNull(episodes.findByEnclosureUrl("https://cdn/none.mp3"))
        assertEquals(setOf("a1", "a2"), episodes.getForPodcast(a).map { it.guid }.toSet())
    }

    @Test
    fun `updating episodes persists every changed field`() = runBlocking {
        val id = podcast("https://a")
        episodes.insertAll(listOf(episode(id, "e1"), episode(id, "e2")))
        val (e1, e2) = episodes.getForPodcast(id).sortedBy { it.guid }

        episodes.update(e1.copy(played = true, playbackPositionSeconds = 42))
        episodes.updateAll(listOf(e2.copy(downloadState = DownloadState.DOWNLOADED, downloadPath = "/f", downloadedBytes = 9)))

        val rows = episodes.getForPodcast(id).associateBy { it.guid }
        assertEquals(true, rows.getValue("e1").played)
        assertEquals(42L, rows.getValue("e1").playbackPositionSeconds)
        assertEquals(DownloadState.DOWNLOADED, rows.getValue("e2").downloadState)
        assertEquals("/f", rows.getValue("e2").downloadPath)
        assertEquals(9L, rows.getValue("e2").downloadedBytes)
    }

    @Test
    fun `updatePosition changes only the position and reports whether a row matched`() = runBlocking {
        val id = podcast("https://a")
        episodes.insertAll(listOf(episode(id, "e1")))
        val before = episodes.getForPodcast(id).single()

        assertEquals(1, episodes.updatePosition(before.id, 77))
        assertEquals(0, episodes.updatePosition(before.id + 100, 5))

        assertEquals(before.copy(playbackPositionSeconds = 77), episodes.getForPodcast(id).single())
    }

    @Test
    fun `guids for a podcast lists only that podcast's episodes`() = runBlocking {
        val id = podcast("https://a")
        val other = podcast("https://b")
        episodes.insertAll(listOf(episode(id, "e1"), episode(id, "e2"), episode(other, "x")))

        assertEquals(setOf("e1", "e2"), episodes.getGuidsForPodcast(id).toSet())
    }

    @Test
    fun `older unplayed returns only unplayed episodes of the podcast published strictly before the cutoff`() = runBlocking {
        val id = podcast("https://a")
        val other = podcast("https://b")
        episodes.insertAll(
            listOf(
                episode(id, "old", published = 100),
                episode(id, "boundary", published = 200),
                episode(id, "new", published = 300),
                episode(id, "oldPlayed", published = 50, played = true),
                episode(id, "undated", published = null),
                episode(other, "otherOld", published = 100),
            ),
        )

        assertEquals(listOf("old"), episodes.getOlderUnplayed(id, publishedBefore = 200).map { it.guid })
    }

    @Test
    fun `unplayed for podcast returns every unplayed episode of that podcast including undated ones`() = runBlocking {
        val id = podcast("https://a")
        val other = podcast("https://b")
        episodes.insertAll(
            listOf(
                episode(id, "old", published = 100),
                episode(id, "new", published = 300),
                episode(id, "undated", published = null),
                episode(id, "played", published = 50, played = true),
                episode(other, "otherUnplayed", published = 100),
            ),
        )

        assertEquals(setOf("old", "new", "undated"), episodes.getUnplayedForPodcast(id).map { it.guid }.toSet())
    }

    @Test
    fun `episodes are selected by download state`() = runBlocking {
        val id = podcast("https://a")
        episodes.insertAll(
            listOf(
                episode(id, "none"),
                episode(id, "queued", state = DownloadState.QUEUED),
                episode(id, "done", state = DownloadState.DOWNLOADED),
                episode(id, "failed", state = DownloadState.FAILED),
            ),
        )

        assertEquals(listOf("done"), episodes.getByDownloadState(DownloadState.DOWNLOADED).map { it.guid })
        val observed = episodes.observeByDownloadStates(listOf(DownloadState.QUEUED, DownloadState.FAILED)).first()
        assertEquals(setOf("queued", "failed"), observed.map { it.guid }.toSet())
        assertEquals(emptyList<EpisodeEntity>(), episodes.observeByDownloadStates(emptyList()).first())
    }

    @Test
    fun `a podcast's episodes are observed newest first`() = runBlocking {
        val id = podcast("https://a")
        val other = podcast("https://b")
        episodes.insertAll(
            listOf(episode(id, "mid", 200), episode(id, "new", 300), episode(id, "old", 100), episode(other, "x", 999)),
        )

        assertEquals(listOf("new", "mid", "old"), episodes.observeForPodcast(id).first().map { it.guid })
    }

    @Test
    fun `unplayed episodes are observed newest first up to the limit`() = runBlocking {
        val id = podcast("https://a")
        episodes.insertAll(
            listOf(
                episode(id, "e1", 100), episode(id, "e2", 200), episode(id, "e3", 300),
                episode(id, "played", 400, played = true),
            ),
        )

        assertEquals(listOf("e3", "e2"), episodes.observeUnplayed(limit = 2).first().map { it.guid })
        assertEquals(listOf("e3", "e2", "e1"), episodes.observeUnplayed(limit = 10).first().map { it.guid })
    }

    @Test
    fun `unplayed episodes are counted across podcasts`() = runBlocking {
        val a = podcast("https://a")
        val b = podcast("https://b")
        assertEquals(0, episodes.observeUnplayedCount().first())

        episodes.insertAll(
            listOf(episode(a, "e1"), episode(a, "e2", played = true), episode(b, "e3"), episode(b, "e4")),
        )

        assertEquals(3, episodes.observeUnplayedCount().first())
    }

    @Test
    fun `stats count unplayed episodes and find the latest publication per podcast`() = runBlocking {
        val a = podcast("https://a")
        val b = podcast("https://b")
        episodes.insertAll(
            listOf(
                episode(a, "a1", 100), episode(a, "a2", 300, played = true), episode(a, "a3", 200),
                episode(b, "b1", published = null, played = true),
            ),
        )

        val stats = episodes.observeStats().first().associateBy { it.podcastId }
        assertEquals(PodcastEpisodeStats(a, unplayed = 2, latestPublishedAtMillis = 300), stats.getValue(a))
        assertEquals(PodcastEpisodeStats(b, unplayed = 0, latestPublishedAtMillis = null), stats.getValue(b))
    }

    // --- sync queue ----------------------------------------------------------------------------

    @Test
    fun `queued actions come back oldest first, ties in insertion order`() = runBlocking {
        queue.insert(action(SyncActionType.PLAY, "https://e/late", at = 300))
        queue.insert(action(SyncActionType.PLAY, "https://e/tie1", at = 100))
        queue.insert(action(SyncActionType.DOWNLOAD, "https://e/tie2", at = 100))

        assertEquals(listOf("https://e/tie1", "https://e/tie2", "https://e/late"), queue.getAll().map { it.episodeUrl })
        assertEquals(3, queue.observeCount().first())
    }

    @Test
    fun `queued actions are deleted by id or by type and episode`() = runBlocking {
        val first = queue.insert(action(SyncActionType.PLAY, "https://e/1", at = 1))
        queue.insert(action(SyncActionType.PLAY, "https://e/2", at = 2))
        queue.insert(action(SyncActionType.DOWNLOAD, "https://e/2", at = 3))

        queue.deleteByTypeAndEpisode(SyncActionType.PLAY, "https://e/2")
        assertEquals(listOf("https://e/1", "https://e/2"), queue.getAll().map { it.episodeUrl })
        assertEquals(SyncActionType.DOWNLOAD, queue.getAll().last().type)

        queue.deleteByIds(listOf(first))
        assertEquals(1, queue.observeCount().first())
    }

    @Test
    fun `queued action fields round trip`() = runBlocking {
        val saved = SyncQueueActionEntity(
            type = SyncActionType.PLAY, feedUrl = "https://f", episodeUrl = "https://e", guid = "g",
            timestampUtcMillis = 5, startedSeconds = 1, positionSeconds = 2, totalSeconds = 3,
        )

        val id = queue.insert(saved)

        assertEquals(saved.copy(id = id), queue.getAll().single())
    }

    // --- sync state and subscription changes ---------------------------------------------------

    @Test
    fun `sync state is absent until saved and upsert replaces it`() = runBlocking {
        assertNull(syncState.get())

        syncState.upsert(SyncStateEntity(subscriptionsSince = 1, episodeActionsSince = 2))
        syncState.upsert(SyncStateEntity(subscriptionsSince = 10, episodeActionsSince = 20))

        assertEquals(SyncStateEntity(subscriptionsSince = 10, episodeActionsSince = 20), syncState.get())
    }

    @Test
    fun `the latest subscription change per feed wins and changes delete by feed url`() = runBlocking {
        changes.upsert(SubscriptionChangeEntity("https://a", subscribed = true, timestampUtcMillis = 1))
        changes.upsert(SubscriptionChangeEntity("https://a", subscribed = false, timestampUtcMillis = 2))
        changes.upsert(SubscriptionChangeEntity("https://b", subscribed = true, timestampUtcMillis = 3))
        changes.upsert(SubscriptionChangeEntity("https://c", subscribed = true, timestampUtcMillis = 4))

        assertEquals(false, changes.getAll().single { it.feedUrl == "https://a" }.subscribed)
        assertEquals(3, changes.getAll().size)

        changes.deleteByFeedUrls(listOf("https://a", "https://b"))
        assertEquals(listOf("https://c"), changes.getAll().map { it.feedUrl })
        assertNotNull(changes.getAll().single())
    }
}
