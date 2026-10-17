// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.sync

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.sonora.core.database.EpisodeEntity
import org.sonora.core.database.FakeEpisodeDao
import org.sonora.core.database.FakePodcastDao
import org.sonora.core.database.FakeSubscriptionChangeDao
import org.sonora.core.database.FakeSyncQueueDao
import org.sonora.core.database.FakeSyncStateDao
import org.sonora.core.database.FakeTransactionRunner
import org.sonora.core.database.MutableClock
import org.sonora.core.database.PodcastEntity
import org.sonora.core.database.SubscriptionChangeEntity
import org.sonora.core.database.SyncQueueActionEntity
import org.sonora.core.database.SyncStateEntity
import org.sonora.core.model.DownloadState
import org.sonora.core.model.SyncActionType

private class FakeSyncProvider : SyncProvider {
    var subscriptions = SubscriptionChanges(emptyList(), emptyList(), 100)
    var episodePage = EpisodeActionsPage(emptyList(), 200)
    val subscriptionSinces = mutableListOf<Long>()
    val episodeSinces = mutableListOf<Long>()
    val pushedSubscriptions = mutableListOf<Pair<List<String>, List<String>>>()
    val pushedActions = mutableListOf<List<RemoteEpisodeAction>>()
    var failPull: Exception? = null
    var failEpisodePush: Exception? = null
    var onEpisodePush: suspend () -> Unit = {}

    override suspend fun pullSubscriptions(since: Long): SubscriptionChanges {
        failPull?.let { throw it }
        subscriptionSinces += since
        return subscriptions
    }

    override suspend fun pushSubscriptions(add: List<String>, remove: List<String>): Long {
        pushedSubscriptions += add to remove
        return 150
    }

    override suspend fun pullEpisodeActions(since: Long): EpisodeActionsPage {
        episodeSinces += since
        return episodePage
    }

    override suspend fun pushEpisodeActions(actions: List<RemoteEpisodeAction>): Long {
        onEpisodePush()
        failEpisodePush?.let { throw it }
        pushedActions += actions
        return 250
    }
}

class SyncCoordinatorTest {
    private val provider = FakeSyncProvider()
    private val podcastDao = FakePodcastDao()
    private val episodeDao = FakeEpisodeDao()
    private val queue = FakeSyncQueueDao()
    private val changes = FakeSubscriptionChangeDao()
    private val state = FakeSyncStateDao()
    private val clock = MutableClock()
    private val coordinator = SyncCoordinator(
        provider, podcastDao, episodeDao, queue, changes, state, FakeTransactionRunner(), clock,
    )

    private val feed = "https://f/feed"
    private val epUrl = "https://f/1.mp3"

    private suspend fun seedEpisode(position: Long = 0, downloaded: Boolean = false): Long {
        val pid = podcastDao.insert(PodcastEntity(feedUrl = feed, title = "Show"))
        episodeDao.insertAll(
            listOf(
                EpisodeEntity(
                    podcastId = pid, guid = "g1", title = "Ep", enclosureUrl = epUrl, durationSeconds = 600,
                    playbackPositionSeconds = position,
                    downloadState = if (downloaded) DownloadState.DOWNLOADED else DownloadState.NONE,
                    downloadPath = if (downloaded) "/data/1.mp3" else null,
                ),
            ),
        )
        return episodeDao.getForPodcast(pid).single().id
    }

    private fun remote(type: SyncActionType, ts: Long, position: Long? = null, total: Long? = 600) =
        RemoteEpisodeAction(feed, epUrl, "g1", type, ts, startedSeconds = 0, positionSeconds = position, totalSeconds = total)

    private suspend fun localPlay(ts: Long, position: Long) {
        queue.insert(
            SyncQueueActionEntity(
                type = SyncActionType.PLAY, feedUrl = feed, episodeUrl = epUrl, guid = "g1",
                timestampUtcMillis = ts, startedSeconds = 0, positionSeconds = position, totalSeconds = 600,
            ),
        )
    }

    // --- subscriptions

    @Test
    fun `remote subscription adds and removes are applied locally and the cursor is stored`() = runTest {
        podcastDao.insert(PodcastEntity(feedUrl = "https://gone/feed", title = "Gone"))
        provider.subscriptions = SubscriptionChanges(listOf("https://new/feed"), listOf("https://gone/feed"), 555)

        val report = coordinator.sync()

        assertEquals(listOf("https://new/feed"), podcastDao.getAll().map { it.feedUrl })
        assertEquals(555L, state.state!!.subscriptionsSince)
        assertEquals(1, report.subscriptionsAdded)
        assertEquals(1, report.subscriptionsRemoved)
    }

    @Test
    fun `pull starts from the stored cursors`() = runTest {
        state.state = SyncStateEntity(subscriptionsSince = 11, episodeActionsSince = 22)
        coordinator.sync()
        assertEquals(listOf(11L), provider.subscriptionSinces)
        assertEquals(listOf(22L), provider.episodeSinces)
    }

    @Test
    fun `remote add of an existing podcast does not duplicate it`() = runTest {
        podcastDao.insert(PodcastEntity(feedUrl = "https://x/feed", title = "Existing"))
        provider.subscriptions = SubscriptionChanges(listOf("https://x/feed"), emptyList(), 1)
        coordinator.sync()
        assertEquals(1, podcastDao.getAll().size)
        assertEquals("Existing", podcastDao.getAll().single().title)
    }

    @Test
    fun `pending local subscription changes are pushed then cleared`() = runTest {
        changes.upsert(SubscriptionChangeEntity("https://add/feed", true, 1))
        changes.upsert(SubscriptionChangeEntity("https://del/feed", false, 2))

        coordinator.sync()

        assertEquals(listOf(listOf("https://add/feed") to listOf("https://del/feed")), provider.pushedSubscriptions)
        assertTrue(changes.rows.isEmpty())
    }

    @Test
    fun `nothing is pushed when there are no local changes`() = runTest {
        coordinator.sync()
        assertTrue(provider.pushedSubscriptions.isEmpty())
        assertTrue(provider.pushedActions.isEmpty())
    }

    @Test
    fun `a pending local subscription change beats the remote change for the same feed`() = runTest {
        podcastDao.insert(PodcastEntity(feedUrl = "https://keep/feed", title = "Keep"))
        changes.upsert(SubscriptionChangeEntity("https://keep/feed", true, 5))
        provider.subscriptions = SubscriptionChanges(
            add = listOf("https://never/feed"), remove = listOf("https://keep/feed"), timestamp = 9,
        )
        changes.upsert(SubscriptionChangeEntity("https://never/feed", false, 5))

        coordinator.sync()

        assertEquals(listOf("https://keep/feed"), podcastDao.getAll().map { it.feedUrl })
    }

    // --- episode actions: conflict resolution

    @Test
    fun `remote PLAY newer than the local PLAY wins and the local action is dropped`() = runTest {
        val id = seedEpisode(position = 50)
        localPlay(ts = 1_000, position = 50)
        provider.episodePage = EpisodeActionsPage(listOf(remote(SyncActionType.PLAY, ts = 2_000, position = 300)), 200)

        coordinator.sync()

        assertEquals(300, episodeDao.findById(id)!!.playbackPositionSeconds)
        assertTrue(queue.getAll().isEmpty())
        assertTrue(provider.pushedActions.isEmpty())
    }

    @Test
    fun `local PLAY newer than the remote PLAY wins and is pushed`() = runTest {
        val id = seedEpisode(position = 400)
        localPlay(ts = 5_000, position = 400)
        provider.episodePage = EpisodeActionsPage(listOf(remote(SyncActionType.PLAY, ts = 2_000, position = 300)), 200)

        coordinator.sync()

        assertEquals(400, episodeDao.findById(id)!!.playbackPositionSeconds)
        val pushed = provider.pushedActions.single().single()
        assertEquals(400L, pushed.positionSeconds)
        assertEquals(5_000L, pushed.timestampUtcMillis)
        assertEquals(epUrl, pushed.episodeUrl)
    }

    @Test
    fun `equal timestamps favour the local action`() = runTest {
        val id = seedEpisode(position = 400)
        localPlay(ts = 2_000, position = 400)
        provider.episodePage = EpisodeActionsPage(listOf(remote(SyncActionType.PLAY, ts = 2_000, position = 300)), 200)

        coordinator.sync()

        assertEquals(400, episodeDao.findById(id)!!.playbackPositionSeconds)
    }

    @Test
    fun `remote PLAY with no local action is applied and nothing is pushed`() = runTest {
        val id = seedEpisode()
        provider.episodePage = EpisodeActionsPage(listOf(remote(SyncActionType.PLAY, ts = 2_000, position = 120)), 200)

        val report = coordinator.sync()

        assertEquals(120, episodeDao.findById(id)!!.playbackPositionSeconds)
        assertEquals(1, report.episodeActionsApplied)
        assertTrue(provider.pushedActions.isEmpty())
    }

    @Test
    fun `the latest of several remote PLAY actions wins`() = runTest {
        val id = seedEpisode()
        provider.episodePage = EpisodeActionsPage(
            listOf(
                remote(SyncActionType.PLAY, ts = 3_000, position = 200),
                remote(SyncActionType.PLAY, ts = 9_000, position = 500),
                remote(SyncActionType.PLAY, ts = 1_000, position = 10),
            ),
            200,
        )

        coordinator.sync()

        assertEquals(500, episodeDao.findById(id)!!.playbackPositionSeconds)
    }

    @Test
    fun `remote PLAY to the end marks the episode played`() = runTest {
        val id = seedEpisode()
        provider.episodePage = EpisodeActionsPage(listOf(remote(SyncActionType.PLAY, 2_000, position = 600, total = 600)), 200)
        coordinator.sync()
        assertTrue(episodeDao.findById(id)!!.played)
    }

    @Test
    fun `remote NEW resets a played episode unless a newer local PLAY exists`() = runTest {
        val id = seedEpisode(position = 300)
        episodeDao.update(episodeDao.findById(id)!!.copy(played = true))
        provider.episodePage = EpisodeActionsPage(listOf(remote(SyncActionType.NEW, ts = 2_000)), 200)

        coordinator.sync()

        val ep = episodeDao.findById(id)!!
        assertEquals(0, ep.playbackPositionSeconds)
        assertFalse(ep.played)
    }

    @Test
    fun `remote DOWNLOAD and DELETE do not touch this device's downloads`() = runTest {
        val id = seedEpisode(downloaded = true)
        provider.episodePage = EpisodeActionsPage(
            listOf(remote(SyncActionType.DELETE, ts = 2_000, total = null)), 200,
        )

        coordinator.sync()

        assertEquals(DownloadState.DOWNLOADED, episodeDao.findById(id)!!.downloadState)
    }

    @Test
    fun `remote actions for unknown episodes are ignored`() = runTest {
        provider.episodePage = EpisodeActionsPage(listOf(remote(SyncActionType.PLAY, 2_000, position = 10)), 200)
        val report = coordinator.sync()
        assertEquals(0, report.episodeActionsApplied)
        assertEquals(200L, state.state!!.episodeActionsSince)
    }

    // --- push + failure behaviour

    @Test
    fun `all pending actions are pushed oldest first, then the queue is cleared`() = runTest {
        seedEpisode()
        queue.insert(SyncQueueActionEntity(type = SyncActionType.DOWNLOAD, feedUrl = feed, episodeUrl = epUrl, timestampUtcMillis = 20))
        localPlay(ts = 10, position = 5)

        val report = coordinator.sync()

        assertEquals(listOf(SyncActionType.PLAY, SyncActionType.DOWNLOAD), provider.pushedActions.single().map { it.type })
        assertTrue(queue.getAll().isEmpty())
        assertEquals(2, report.episodeActionsPushed)
    }

    @Test
    fun `actions queued while the push is in flight are kept`() = runTest {
        seedEpisode()
        localPlay(ts = 10, position = 5)
        provider.onEpisodePush = {
            queue.insert(SyncQueueActionEntity(type = SyncActionType.DELETE, feedUrl = feed, episodeUrl = epUrl, timestampUtcMillis = 30))
        }

        coordinator.sync()

        assertEquals(listOf(SyncActionType.DELETE), queue.getAll().map { it.type })
    }

    @Test
    fun `a failed push keeps the queue intact and propagates the error`() = runTest {
        seedEpisode()
        localPlay(ts = 10, position = 5)
        provider.failEpisodePush = SyncException("boom")

        assertThrows<SyncException> { coordinator.sync() }

        assertEquals(1, queue.getAll().size)
    }

    @Test
    fun `a failed pull leaves local state and cursors untouched`() = runTest {
        seedEpisode()
        localPlay(ts = 10, position = 5)
        provider.failPull = SyncAuthException("401")

        assertThrows<SyncAuthException> { coordinator.sync() }

        assertNull(state.state)
        assertEquals(1, queue.getAll().size)
    }

    @Test
    fun `episode cursor is stored after a successful sync`() = runTest {
        provider.episodePage = EpisodeActionsPage(emptyList(), 4242)
        coordinator.sync()
        assertEquals(4242L, state.state!!.episodeActionsSince)
    }
}
