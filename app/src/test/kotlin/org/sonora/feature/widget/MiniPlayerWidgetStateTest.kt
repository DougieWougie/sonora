// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.widget

import app.cash.turbine.test
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.sonora.core.database.FakeEpisodeDao
import org.sonora.core.database.FakePodcastDao
import org.sonora.core.media.PlaybackState
import org.sonora.testutil.episodeEntity
import org.sonora.testutil.showId

class MiniPlayerWidgetStateTest {
    private val podcastDao = FakePodcastDao()
    private val episodeDao = FakeEpisodeDao()
    private val playback = MutableStateFlow(PlaybackState())

    private fun states() = miniPlayerWidgetStates(playback, episodeDao, podcastDao)

    private suspend fun seedEpisode(title: String = "Ep one", episodeImage: String? = null): Long {
        val podcastId = podcastDao.showId()
        episodeDao.insertAll(listOf(episodeEntity(podcastId, "g", title = title).copy(imageUrl = episodeImage)))
        return episodeDao.getForPodcast(podcastId).single().id
    }

    @Test
    fun `is idle when nothing is loaded`() = runTest {
        states().test {
            assertEquals(MiniPlayerWidgetState.Idle, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `shows the loaded episode and the podcast it belongs to`() = runTest {
        val id = seedEpisode(title = "Ep one")
        playback.value = PlaybackState(episodeId = id, isPlaying = true)
        states().test {
            val state = awaitItem() as MiniPlayerWidgetState.Loaded
            assertEquals("Ep one", state.episodeTitle)
            assertEquals(true, state.isPlaying)
            assertEquals(podcastDao.findById(episodeDao.findById(id)!!.podcastId)!!.title, state.podcastTitle)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `prefers episode artwork and falls back to the podcast's`() = runTest {
        val withOwn = seedEpisode(episodeImage = "https://example.org/ep.jpg")
        playback.value = PlaybackState(episodeId = withOwn)
        states().test {
            assertEquals("https://example.org/ep.jpg", (awaitItem() as MiniPlayerWidgetState.Loaded).artworkUrl)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `falls back to the podcast artwork when the episode has none`() = runTest {
        val id = seedEpisode(episodeImage = null)
        val podcastId = episodeDao.findById(id)!!.podcastId
        podcastDao.update(podcastDao.findById(podcastId)!!.copy(imageUrl = "https://example.org/show.jpg"))
        playback.value = PlaybackState(episodeId = id)
        states().test {
            assertEquals("https://example.org/show.jpg", (awaitItem() as MiniPlayerWidgetState.Loaded).artworkUrl)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `follows play and pause`() = runTest {
        val id = seedEpisode()
        playback.value = PlaybackState(episodeId = id, isPlaying = true)
        states().test {
            assertEquals(true, (awaitItem() as MiniPlayerWidgetState.Loaded).isPlaying)
            playback.value = PlaybackState(episodeId = id, isPlaying = false)
            assertEquals(false, (awaitItem() as MiniPlayerWidgetState.Loaded).isPlaying)
            playback.value = PlaybackState()
            assertEquals(MiniPlayerWidgetState.Idle, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `is idle when the loaded episode no longer exists`() = runTest {
        playback.value = PlaybackState(episodeId = 9_999)
        states().test {
            assertEquals(MiniPlayerWidgetState.Idle, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}

class RedrawOnChangeTest {
    @Test
    fun `redraws for every state`() = runTest {
        var redraws = 0
        kotlinx.coroutines.flow.flowOf(MiniPlayerWidgetState.Idle, MiniPlayerWidgetState.Idle).redrawOnChange { redraws++ }
        assertEquals(2, redraws)
    }

    @Test
    fun `a failed redraw does not stop later ones`() = runTest {
        var attempts = 0
        kotlinx.coroutines.flow.flowOf(MiniPlayerWidgetState.Idle, MiniPlayerWidgetState.Idle).redrawOnChange {
            attempts++
            if (attempts == 1) error("widget host went away")
        }
        assertEquals(2, attempts)
    }

    @Test
    fun `a redraw that hangs does not hold up the next state`() = runTest(timeout = kotlin.time.Duration.parse("5s")) {
        val drawn = mutableListOf<MiniPlayerWidgetState>()
        val loaded = MiniPlayerWidgetState.Loaded(1, "Ep", null, null, isPlaying = true)
        val states = kotlinx.coroutines.flow.flow {
            emit(MiniPlayerWidgetState.Idle)
            kotlinx.coroutines.yield()
            emit(loaded)
        }
        var current: MiniPlayerWidgetState = MiniPlayerWidgetState.Idle
        states.onEach { current = it }.redrawOnChange {
            if (current == MiniPlayerWidgetState.Idle) kotlinx.coroutines.awaitCancellation()
            drawn += current
        }
        assertEquals(listOf<MiniPlayerWidgetState>(loaded), drawn)
    }

    @Test
    fun `a failed redraw is reported`() = runTest {
        val failures = mutableListOf<Exception>()
        kotlinx.coroutines.flow.flowOf(MiniPlayerWidgetState.Idle).redrawOnChange(onFailure = { failures += it }) {
            error("widget host went away")
        }
        assertEquals(listOf("widget host went away"), failures.map { it.message })
    }

    @Test
    fun `cancelling the collector stops it and is not reported as a failure`() = runTest {
        val failures = mutableListOf<Exception>()
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val job = launch {
            kotlinx.coroutines.flow.flowOf(MiniPlayerWidgetState.Idle).redrawOnChange(onFailure = { failures += it }) {
                started.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }
        }
        started.await()
        job.cancel()
        job.join()
        assertEquals(true, job.isCancelled)
        assertEquals(emptyList<Exception>(), failures)
    }
}
