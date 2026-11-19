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
    fun `does nothing when nothing is loaded`() = runTest {
        port.togglePlayPause()
        assertEquals(emptyList<String>(), port.calls)
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
    }

    private val noParameters = androidx.glance.action.actionParametersOf()

    @org.junit.Test
    fun `play pause callback toggles playback`() = runTest {
        port.stateFlow.value = PlaybackState(episodeId = 3, isPlaying = true)
        PlayPauseAction().onAction(context, glanceId, noParameters)
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
    }

    @org.junit.Before fun drivePort() { widgetPlaybackPort = { port } }
    @org.junit.After fun restore() { widgetPlaybackPort = null }

    @org.junit.Test
    fun `every callback drives the player from Main`() = runTest {
        PlayPauseAction().onAction(context, glanceId, noParameters)
        PreviousAction().onAction(context, glanceId, noParameters)
        NextAction().onAction(context, glanceId, noParameters)
        assertEquals(List(3) { kotlinx.coroutines.Dispatchers.Main.immediate }, dispatchers)
    }
}
