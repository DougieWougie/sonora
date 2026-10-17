// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.media

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.sonora.core.database.EpisodeEntity
import org.sonora.core.database.EpisodeRepository
import org.sonora.core.database.FakeEpisodeDao
import org.sonora.core.database.FakePodcastDao
import org.sonora.core.database.FakeSyncQueueDao
import org.sonora.core.database.FakeTransactionRunner
import org.sonora.core.database.MutableClock
import org.sonora.core.database.PodcastEntity
import org.sonora.core.model.DownloadState
import org.sonora.core.model.SyncActionType

class FakePlayerEngine : PlayerEngine {
    val calls = mutableListOf<String>()
    var preparedUri: String? = null
    var preparedStartMs: Long = -1
    var preparedMetadata: PlaybackMetadata? = null
    override var positionMs: Long = 0
    override var durationMs: Long = 0
    override var bufferedMs: Long = 0
    var appliedSpeed = 1f
    var silenceSkipping = false

    override fun prepare(uri: String, startPositionMs: Long, metadata: PlaybackMetadata) {
        preparedUri = uri
        preparedMetadata = metadata
        preparedStartMs = startPositionMs
        positionMs = startPositionMs
        calls += "prepare"
    }

    override fun play() { calls += "play" }
    override fun pause() { calls += "pause" }
    override fun stop() { calls += "stop" }
    override fun seekTo(positionMs: Long) {
        this.positionMs = positionMs
        calls += "seek:$positionMs"
    }

    override fun setSpeed(speed: Float) { appliedSpeed = speed }
    override fun setSkipSilence(enabled: Boolean) { silenceSkipping = enabled }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackStateManagerTest {
    private val podcastDao = FakePodcastDao()
    private val episodeDao = FakeEpisodeDao()
    private val queue = FakeSyncQueueDao()
    private val clock = MutableClock()
    private val repo = EpisodeRepository(podcastDao, episodeDao, queue, FakeTransactionRunner(), clock)
    private val engine = FakePlayerEngine()
    private var existingFiles = emptySet<String>()
    private val completed = mutableListOf<Long>()

    private var allowCleartext = false
    private val playQueue = PersistentPlayQueue(FakeQueueStorage())

    private fun TestScope.manager() = PlaybackStateManager(
        engine = engine,
        episodeDao = episodeDao,
        podcastDao = podcastDao,
        episodeRepository = repo,
        fileExists = { it in existingFiles },
        scope = backgroundScope,
        allowCleartext = allowCleartext,
        queue = playQueue,
        onEpisodeCompleted = { completed += it },
    )

    private suspend fun seed(
        duration: Long? = 600,
        position: Long = 0,
        state: DownloadState = DownloadState.NONE,
        path: String? = null,
        guid: String = "g1",
    ): Long {
        val podcastId = podcastDao.findByFeedUrl("https://f/feed")?.id
            ?: podcastDao.insert(PodcastEntity(feedUrl = "https://f/feed", title = "Show"))
        episodeDao.insertAll(
            listOf(
                EpisodeEntity(
                    podcastId = podcastId, guid = guid, title = "Ep $guid", enclosureUrl = "https://cdn/$guid.mp3",
                    durationSeconds = duration, playbackPositionSeconds = position,
                    downloadState = state, downloadPath = path,
                ),
            ),
        )
        return episodeDao.getForPodcast(podcastId).first { it.guid == guid }.id
    }

    // --- source resolution -------------------------------------------------------------------

    private suspend fun seedWithUrl(url: String): Long {
        val id = seed()
        episodeDao.update(episodeDao.findById(id)!!.copy(enclosureUrl = url))
        return id
    }

    @Test
    fun `refuses to stream a cleartext enclosure unless cleartext is allowed`() = runTest {
        val id = seedWithUrl("http://cdn/g1.mp3")

        assertThrows<IllegalStateException> { manager().play(id) }
        assertTrue(engine.calls.isEmpty())
        assertFalse(manager().state.value.isPlaying)
    }

    @Test
    fun `streams a cleartext enclosure when the user opted in`() = runTest {
        allowCleartext = true
        val id = seedWithUrl("http://cdn/g1.mp3")

        manager().play(id)

        assertEquals("http://cdn/g1.mp3", engine.preparedUri)
    }

    @Test
    fun `refuses non-http schemes in the enclosure even when cleartext is allowed`() = runTest {
        allowCleartext = true
        for (url in listOf("file:///etc/passwd", "content://x/y", "rtmp://h/s")) {
            val id = seedWithUrl(url)
            assertThrows<IllegalStateException> { manager().play(id) }
        }
        assertTrue(engine.calls.isEmpty())
    }

    @Test
    fun `streams the enclosure url and resumes from the saved position`() = runTest {
        val id = seed(position = 125)
        val m = manager()

        m.play(id)

        assertEquals("https://cdn/g1.mp3", engine.preparedUri)
        assertEquals(125_000, engine.preparedStartMs)
        assertEquals(listOf("prepare", "play"), engine.calls)
        assertTrue(m.state.value.isPlaying)
        assertEquals(id, m.state.value.episodeId)
    }

    @Test
    fun `plays the local file when downloaded and present on disk`() = runTest {
        val id = seed(state = DownloadState.DOWNLOADED, path = "/data/ep/g1.mp3")
        existingFiles = setOf("/data/ep/g1.mp3")

        manager().play(id)

        assertEquals("file:///data/ep/g1.mp3", engine.preparedUri)
    }

    @Test
    fun `falls back to streaming when the downloaded file is missing`() = runTest {
        val id = seed(state = DownloadState.DOWNLOADED, path = "/data/ep/g1.mp3")
        existingFiles = emptySet()

        manager().play(id)

        assertEquals("https://cdn/g1.mp3", engine.preparedUri)
    }

    @Test
    fun `ignores a download path when the episode is not in the DOWNLOADED state`() = runTest {
        val id = seed(state = DownloadState.DOWNLOADING, path = "/data/ep/g1.mp3")
        existingFiles = setOf("/data/ep/g1.mp3")

        manager().play(id)

        assertEquals("https://cdn/g1.mp3", engine.preparedUri)
    }

    @Test
    fun `passes episode and podcast metadata to the engine for notifications`() = runTest {
        val id = seed()

        manager().play(id)

        assertEquals(
            PlaybackMetadata(mediaId = id.toString(), title = "Ep g1", artist = "Show", artworkUrl = null),
            engine.preparedMetadata,
        )
    }

    @Test
    fun `playing an unknown episode fails`() = runTest {
        assertThrows<NoSuchElementException> { manager().play(99) }
    }

    // --- periodic progress -------------------------------------------------------------------

    @Test
    fun `persists progress every 10 seconds while playing without queuing sync actions`() = runTest {
        val id = seed()
        val m = manager()
        m.play(id)

        engine.positionMs = 15_000
        advanceTimeBy(9_999)
        assertEquals(0, episodeDao.findById(id)!!.playbackPositionSeconds)
        advanceTimeBy(2)
        assertEquals(15, episodeDao.findById(id)!!.playbackPositionSeconds)

        engine.positionMs = 25_000
        advanceTimeBy(10_000)
        assertEquals(25, episodeDao.findById(id)!!.playbackPositionSeconds)
        assertTrue(queue.getAll().isEmpty())
    }

    @Test
    fun `stops persisting once paused`() = runTest {
        val id = seed()
        val m = manager()
        m.play(id)
        engine.positionMs = 5_000
        m.pause()

        engine.positionMs = 50_000
        advanceTimeBy(60_000)

        assertEquals(5, episodeDao.findById(id)!!.playbackPositionSeconds)
    }

    // --- sync actions ------------------------------------------------------------------------

    @Test
    fun `pause persists immediately and queues a PLAY action`() = runTest {
        val id = seed(position = 100)
        clock.nowMillis = 1_800_000_000_000
        val m = manager()
        m.play(id)
        engine.positionMs = 130_000

        m.pause()

        assertEquals(listOf("prepare", "play", "pause"), engine.calls)
        assertFalse(m.state.value.isPlaying)
        assertEquals(130, episodeDao.findById(id)!!.playbackPositionSeconds)
        val action = queue.getAll().single()
        assertEquals(SyncActionType.PLAY, action.type)
        assertEquals(100L, action.startedSeconds)
        assertEquals(130L, action.positionSeconds)
        assertEquals(600L, action.totalSeconds)
        assertEquals(1_800_000_000_000, action.timestampUtcMillis)
    }

    @Test
    fun `repeated pauses keep a single pending PLAY action`() = runTest {
        val id = seed()
        val m = manager()
        m.play(id)
        engine.positionMs = 10_000
        m.pause()
        m.play(id)
        engine.positionMs = 20_000
        m.pause()

        val action = queue.getAll().single()
        assertEquals(20L, action.positionSeconds)
        assertEquals(10L, action.startedSeconds)
    }

    @Test
    fun `stop persists, queues a PLAY action and releases the episode`() = runTest {
        val id = seed()
        val m = manager()
        m.play(id)
        engine.positionMs = 42_000

        m.stop()

        assertEquals("stop", engine.calls.last())
        assertNull(m.state.value.episodeId)
        assertFalse(m.state.value.isPlaying)
        assertEquals(42, episodeDao.findById(id)!!.playbackPositionSeconds)
        assertEquals(42L, queue.getAll().single().positionSeconds)
    }

    @Test
    fun `seek moves the engine, persists immediately and queues a PLAY action`() = runTest {
        val id = seed(position = 10)
        val m = manager()
        m.play(id)

        m.seekTo(300_000)

        assertEquals(300_000, engine.positionMs)
        assertEquals(300, episodeDao.findById(id)!!.playbackPositionSeconds)
        val action = queue.getAll().single()
        assertEquals(300L, action.positionSeconds)
        assertEquals(10L, action.startedSeconds)
    }

    @Test
    fun `backwards seek never reports started after position`() = runTest {
        val id = seed(position = 200)
        val m = manager()
        m.play(id)

        m.seekTo(50_000)

        val action = queue.getAll().single()
        assertEquals(50L, action.startedSeconds)
        assertEquals(50L, action.positionSeconds)
    }

    @Test
    fun `pausing within the last 30 seconds counts as completed`() = runTest {
        val id = seed(duration = 600)
        val m = manager()
        m.play(id)
        engine.positionMs = 575_000

        m.pause()

        val episode = episodeDao.findById(id)!!
        assertTrue(episode.played)
        assertEquals(600, episode.playbackPositionSeconds)
        assertEquals(600L, queue.getAll().single().positionSeconds)
    }

    @Test
    fun `pausing just before the completion window is not completed`() = runTest {
        val id = seed(duration = 600)
        val m = manager()
        m.play(id)
        engine.positionMs = 569_000

        m.pause()

        assertFalse(episodeDao.findById(id)!!.played)
        assertEquals(569L, queue.getAll().single().positionSeconds)
    }

    @Test
    fun `reaching the end of the media completes the episode and stops the ticker`() = runTest {
        val id = seed(duration = 600)
        val m = manager()
        m.play(id)
        engine.positionMs = 600_000

        m.onPlaybackEnded()

        assertTrue(episodeDao.findById(id)!!.played)
        assertFalse(m.state.value.isPlaying)
        assertEquals(600L, queue.getAll().single().positionSeconds)
    }

    @Test
    fun `notifies once when an episode becomes completed and not for ordinary pauses`() = runTest {
        val id = seed(duration = 600)
        val m = manager()
        m.play(id)
        engine.positionMs = 100_000
        m.pause()
        assertTrue(completed.isEmpty())

        m.play(id)
        engine.positionMs = 590_000
        m.pause()
        m.play(id)
        m.pause()

        assertEquals(listOf(id), completed)
    }

    @Test
    fun `unknown duration is never treated as completed`() = runTest {
        val id = seed(duration = null)
        val m = manager()
        m.play(id)
        engine.positionMs = 9_999_000

        m.pause()

        assertFalse(episodeDao.findById(id)!!.played)
    }

    @Test
    fun `switching episodes flushes the previous one first`() = runTest {
        val first = seed(guid = "g1")
        val second = seed(guid = "g2")
        val m = manager()
        m.play(first)
        engine.positionMs = 77_000

        m.play(second)

        assertEquals(77, episodeDao.findById(first)!!.playbackPositionSeconds)
        assertEquals("https://cdn/g2.mp3", engine.preparedUri)
        assertEquals(second, m.state.value.episodeId)
        assertEquals(77L, queue.getAll().single { it.episodeUrl == "https://cdn/g1.mp3" }.positionSeconds)
    }

    // --- queue -------------------------------------------------------------------------------

    @Test
    fun `starting an episode takes it off the queue`() = runTest {
        val a = seed(guid = "a"); val b = seed(guid = "b")
        playQueue.enqueue(a); playQueue.enqueue(b)

        manager().play(a)

        assertEquals(listOf(b), playQueue.items.value)
    }

    @Test
    fun `when an episode ends the next queued one starts`() = runTest {
        val a = seed(guid = "a"); val b = seed(guid = "b")
        playQueue.enqueue(b)
        val m = manager()
        m.play(a)

        m.onPlaybackEnded()

        assertEquals("https://cdn/b.mp3", engine.preparedUri)
        assertEquals(b, m.state.value.episodeId)
        assertTrue(m.state.value.isPlaying)
        assertTrue(playQueue.items.value.isEmpty())
    }

    @Test
    fun `when the queue is empty the player just stops at the end`() = runTest {
        val a = seed(guid = "a")
        val m = manager()
        m.play(a)

        m.onPlaybackEnded()

        assertFalse(m.state.value.isPlaying)
        assertEquals(a, m.state.value.episodeId)
    }

    @Test
    fun `a queued episode that no longer exists is skipped`() = runTest {
        val a = seed(guid = "a"); val c = seed(guid = "c")
        playQueue.enqueue(9_999); playQueue.enqueue(c)
        val m = manager()
        m.play(a)

        m.onPlaybackEnded()

        assertEquals(c, m.state.value.episodeId)
    }

    // --- progress ----------------------------------------------------------------------------

    @Test
    fun `progress is empty until an episode is loaded`() = runTest {
        manager().progress().test {
            assertEquals(PlaybackProgress.None, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `progress follows the engine position, buffer and duration`() = runTest {
        val id = seed(duration = 600)
        val m = manager()
        m.play(id)
        engine.durationMs = 590_000

        m.progress().test {
            assertEquals(PlaybackProgress(0, 0, 590_000), awaitItem())
            engine.positionMs = 5_000
            engine.bufferedMs = 20_000
            assertEquals(PlaybackProgress(5_000, 20_000, 590_000), awaitItem())
            engine.positionMs = 6_000
            assertEquals(PlaybackProgress(6_000, 20_000, 590_000), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `progress falls back to the feed duration while the engine does not know it yet`() = runTest {
        val id = seed(duration = 600)
        val m = manager()
        m.play(id)
        engine.durationMs = 0

        m.progress().test {
            assertEquals(600_000, awaitItem().durationMs)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- speed, silence, sleep timer ---------------------------------------------------------

    @Test
    fun `non-finite speeds are ignored`() = runTest {
        val m = manager()
        m.setSpeed(1.5f)

        m.setSpeed(Float.NaN)
        m.setSpeed(Float.POSITIVE_INFINITY)

        assertEquals(1.5f, m.state.value.speed)
        assertEquals(1.5f, engine.appliedSpeed)
    }

    @Test
    fun `speed is clamped to 0_5x - 3_0x and forwarded to the engine`() = runTest {
        val m = manager()

        m.setSpeed(1.5f)
        assertEquals(1.5f, engine.appliedSpeed)
        assertEquals(1.5f, m.state.value.speed)

        m.setSpeed(0.1f)
        assertEquals(0.5f, engine.appliedSpeed)
        m.setSpeed(9f)
        assertEquals(3.0f, m.state.value.speed)
    }

    @Test
    fun `skip silence toggles on the engine and in state`() = runTest {
        val m = manager()

        m.setSkipSilence(true)
        assertTrue(engine.silenceSkipping)
        assertTrue(m.state.value.skipSilence)
        m.setSkipSilence(false)
        assertFalse(engine.silenceSkipping)
    }

    @Test
    fun `sleep timer counts down and pauses playback when it expires`() = runTest {
        val id = seed()
        val m = manager()
        m.play(id)
        m.startSleepTimer(60_000)
        assertEquals(60_000L, m.state.value.sleepTimerRemainingMs)

        advanceTimeBy(30_001)
        assertTrue(m.state.value.sleepTimerRemainingMs!! in 1..30_000)
        assertTrue(m.state.value.isPlaying)

        engine.positionMs = 61_000
        advanceTimeBy(30_000)
        assertFalse(m.state.value.isPlaying)
        assertNull(m.state.value.sleepTimerRemainingMs)
        assertEquals("pause", engine.calls.last())
        assertEquals(61L, queue.getAll().single().positionSeconds)
    }

    @Test
    fun `cancelling the sleep timer keeps playing`() = runTest {
        val id = seed()
        val m = manager()
        m.play(id)
        m.startSleepTimer(60_000)

        m.cancelSleepTimer()
        advanceTimeBy(120_000)

        assertTrue(m.state.value.isPlaying)
        assertNull(m.state.value.sleepTimerRemainingMs)
    }

    @Test
    fun `restarting the sleep timer replaces the previous one`() = runTest {
        val id = seed()
        val m = manager()
        m.play(id)
        m.startSleepTimer(10_000)
        advanceTimeBy(5_000)
        m.startSleepTimer(60_000)

        advanceTimeBy(20_000)

        assertTrue(m.state.value.isPlaying)
    }

    @Test
    fun `non-positive sleep timer is rejected`() = runTest {
        assertThrows<IllegalArgumentException> { manager().startSleepTimer(0) }
    }
}
