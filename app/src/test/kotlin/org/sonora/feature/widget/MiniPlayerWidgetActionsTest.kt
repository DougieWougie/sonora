// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.widget

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.sonora.core.media.PlaybackState
import org.sonora.testutil.FakePlaybackPort

class MiniPlayerWidgetActionsTest {
    private val port = FakePlaybackPort()

    @Test
    fun `pauses while playing`() = runTest {
        port.stateFlow.value = PlaybackState(episodeId = 7, isPlaying = true)
        port.togglePlayPause()
        assertEquals(listOf("pause"), port.calls)
    }

    @Test
    fun `resumes the loaded episode while paused`() = runTest {
        port.stateFlow.value = PlaybackState(episodeId = 7, isPlaying = false)
        port.togglePlayPause()
        assertEquals(listOf("play:7"), port.calls)
    }

    @Test
    fun `does nothing when nothing is loaded and nothing is remembered`() = runTest {
        port.togglePlayPause()
        assertEquals(emptyList<String>(), port.calls)
    }

    @Test
    fun `plays the remembered episode when the player is empty`() = runTest {
        port.togglePlayPause(recalled = 5)
        assertEquals(listOf("play:5"), port.calls)
    }

    @Test
    fun `prefers the loaded episode over the remembered one`() = runTest {
        port.stateFlow.value = PlaybackState(episodeId = 7, isPlaying = false)
        port.togglePlayPause(recalled = 5)
        assertEquals(listOf("play:7"), port.calls)
    }
}

@org.junit.runner.RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class MiniPlayerWidgetCallbacksTest {
    private val context: android.content.Context = androidx.test.core.app.ApplicationProvider.getApplicationContext()
    private val port = FakePlaybackPort()
    private val glanceId = object : androidx.glance.GlanceId {}

    @org.junit.Before
    fun drivePort() {
        widgetPlaybackPort = { port }
    }

    @org.junit.After
    fun restore() {
        widgetPlaybackPort = null
        widgetServiceStarter = null
    }

    private val noParameters = androidx.glance.action.actionParametersOf()

    @org.junit.Test
    fun `play pause callback toggles playback`() = runTest {
        port.stateFlow.value = PlaybackState(episodeId = 3, isPlaying = true)
        PlayPauseAction().onAction(context, glanceId, noParameters)
        assertEquals(listOf("pause"), port.calls)
    }

    @org.junit.Test
    fun `play pause callback starts the service, plays the recalled episode, then lets go of the service`() = runTest {
        val events = mutableListOf<String>()
        widgetServiceStarter = {
            events += "bind"
            AutoCloseable { events += "release" }
        }
        val recalled = androidx.glance.action.actionParametersOf(RECALLED_EPISODE_KEY to 5L)
        PlayPauseAction().onAction(context, glanceId, recalled)
        assertEquals(listOf("play:5"), port.calls)
        assertEquals(listOf("bind", "release"), events)
    }

    @org.junit.Test
    fun `play pause callback does not start the service when an episode is already loaded`() = runTest {
        var bound = false
        widgetServiceStarter = { bound = true; AutoCloseable {} }
        port.stateFlow.value = PlaybackState(episodeId = 3, isPlaying = true)
        PlayPauseAction().onAction(context, glanceId, androidx.glance.action.actionParametersOf(RECALLED_EPISODE_KEY to 5L))
        assertEquals(false, bound)
        assertEquals(listOf("pause"), port.calls)
    }

    @org.junit.Test
    fun `previous callback goes back`() = runTest {
        PreviousAction().onAction(context, glanceId, noParameters)
        assertEquals(listOf("previous"), port.calls)
    }

    @org.junit.Test
    fun `next callback skips ahead`() = runTest {
        NextAction().onAction(context, glanceId, noParameters)
        assertEquals(listOf("next"), port.calls)
    }

    @org.junit.Test
    fun `rewind callback goes back ten seconds`() = runTest {
        port.progressFlow.value = org.sonora.core.media.PlaybackProgress(60_000, 60_000, 600_000)
        RewindAction().onAction(context, glanceId, noParameters)
        assertEquals(listOf("seek:50000"), port.calls)
    }

    @org.junit.Test
    fun `forward callback skips thirty seconds`() = runTest {
        port.progressFlow.value = org.sonora.core.media.PlaybackProgress(60_000, 60_000, 600_000)
        ForwardAction().onAction(context, glanceId, noParameters)
        assertEquals(listOf("seek:90000"), port.calls)
    }
}

/** Glance runs callbacks on a background dispatcher, but the player may only be touched from Main. */
@org.junit.runner.RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class MiniPlayerWidgetThreadingTest {
    private val context: android.content.Context = androidx.test.core.app.ApplicationProvider.getApplicationContext()
    private val glanceId = object : androidx.glance.GlanceId {}
    private val noParameters = androidx.glance.action.actionParametersOf()
    private val dispatchers = mutableListOf<kotlin.coroutines.ContinuationInterceptor?>()

    private val port = object : org.sonora.core.media.PlaybackPort by FakePlaybackPort() {
        override val state = kotlinx.coroutines.flow.MutableStateFlow(PlaybackState(episodeId = 1, isPlaying = true))
        override suspend fun pause() { dispatchers += kotlin.coroutines.coroutineContext[kotlin.coroutines.ContinuationInterceptor] }
        override suspend fun previous() { dispatchers += kotlin.coroutines.coroutineContext[kotlin.coroutines.ContinuationInterceptor] }
        override suspend fun next() { dispatchers += kotlin.coroutines.coroutineContext[kotlin.coroutines.ContinuationInterceptor] }
        override fun progress() = kotlinx.coroutines.flow.flowOf(org.sonora.core.media.PlaybackProgress(60_000, 60_000, 600_000))
        override suspend fun seekTo(positionMs: Long) { dispatchers += kotlin.coroutines.coroutineContext[kotlin.coroutines.ContinuationInterceptor] }
    }

    @org.junit.Before fun drivePort() { widgetPlaybackPort = { port } }
    @org.junit.After fun restore() { widgetPlaybackPort = null }

    @org.junit.Test
    fun `every callback drives the player from Main`() = runTest {
        PlayPauseAction().onAction(context, glanceId, noParameters)
        PreviousAction().onAction(context, glanceId, noParameters)
        NextAction().onAction(context, glanceId, noParameters)
        RewindAction().onAction(context, glanceId, noParameters)
        ForwardAction().onAction(context, glanceId, noParameters)
        assertEquals(List(5) { kotlinx.coroutines.Dispatchers.Main.immediate }, dispatchers)
    }
}

class MiniPlayerSeekTest {
    private val port = FakePlaybackPort()

    private fun at(positionMs: Long, durationMs: Long = 600_000) {
        port.progressFlow.value = org.sonora.core.media.PlaybackProgress(positionMs, positionMs, durationMs)
    }

    @org.junit.jupiter.api.Test
    fun `rewinds by the given amount`() = runTest {
        at(60_000)
        port.seekBy(-10_000)
        assertEquals(listOf("seek:50000"), port.calls)
    }

    @org.junit.jupiter.api.Test
    fun `skips forward by the given amount`() = runTest {
        at(60_000)
        port.seekBy(30_000)
        assertEquals(listOf("seek:90000"), port.calls)
    }

    @org.junit.jupiter.api.Test
    fun `does not rewind past the start`() = runTest {
        at(4_000)
        port.seekBy(-10_000)
        assertEquals(listOf("seek:0"), port.calls)
    }

    @org.junit.jupiter.api.Test
    fun `does not skip past the end`() = runTest {
        at(590_000, durationMs = 600_000)
        port.seekBy(30_000)
        assertEquals(listOf("seek:600000"), port.calls)
    }

    @org.junit.jupiter.api.Test
    fun `does nothing when nothing is loaded`() = runTest {
        port.seekBy(30_000)
        assertEquals(emptyList<String>(), port.calls)
    }

    @org.junit.jupiter.api.Test
    fun `uses the same intervals as the notification`() {
        assertEquals(10_000L, REWIND_MS)
        assertEquals(30_000L, FORWARD_MS)
    }
}

@org.junit.runner.RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class PrefsLastPlayedStoreTest {
    private val context: android.content.Context = androidx.test.core.app.ApplicationProvider.getApplicationContext()

    @org.junit.Test
    fun `remembers nothing at first, then the last episode, across instances`() {
        context.getSharedPreferences("widget", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        assertEquals(null, PrefsLastPlayedStore(context).recall())
        PrefsLastPlayedStore(context).remember(42)
        assertEquals(42L, PrefsLastPlayedStore(context).recall())
    }
}
