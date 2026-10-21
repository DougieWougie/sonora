// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.media

import android.content.Context
import android.os.Bundle
import android.os.Looper
import androidx.core.os.bundleOf
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import java.time.Duration
import org.junit.Assert.assertEquals
import org.robolectric.Shadows.shadowOf
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import javax.inject.Inject
import org.junit.After
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.sonora.testutil.FakeAndroidKeyStore

@UnstableApi
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class)
class PodcastMediaSessionServiceTest {
    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var hub: PlaybackHub
    @Inject lateinit var playQueue: PlayQueue

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var controller: org.robolectric.android.controller.ServiceController<PodcastMediaSessionService>
    private lateinit var service: PodcastMediaSessionService

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        FakeAndroidKeyStore.install()
        hilt.inject()
        controller = Robolectric.buildService(PodcastMediaSessionService::class.java).create()
        service = controller.get()
    }

    @After
    fun tearDown() {
        connected.forEach { it.release() }
        controller.destroy()
        FakeAndroidKeyStore.uninstall()
    }

    private fun session() = service.onGetSession(mockk())

    @Test
    fun `creating the service opens a media session and attaches playback`() {
        assertNotNull(session())
    }

    @Test
    fun `tapping the playback notification opens the app`() {
        val activity = session()!!.sessionActivity
        assertNotNull(activity)
        val intent = shadowOf(activity).savedIntent
        assertEquals(org.sonora.MainActivity::class.java.name, intent.component?.className)
        assertTrue(shadowOf(activity).isActivity)
    }

    @Test
    fun `destroying the service releases the session so another can start`() {
        controller.destroy()
        controller = Robolectric.buildService(PodcastMediaSessionService::class.java).create()
        service = controller.get()

        assertNotNull(session())
    }

    // --- commands from a controller in our own package ----------------------------------------------

    private fun connect(): MediaController {
        val future = MediaController.Builder(context, session()!!.token).buildAsync()
        val deadline = System.currentTimeMillis() + 10_000
        while (!future.isDone && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            Thread.sleep(10)
        }
        return future.get().also { connected += it }
    }

    private val connected = mutableListOf<MediaController>()

    private fun send(controller: MediaController, command: SessionCommand, args: Bundle = Bundle.EMPTY): SessionResult {
        val future = controller.sendCustomCommand(command, args)
        val deadline = System.currentTimeMillis() + 10_000
        while (!future.isDone && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            Thread.sleep(10)
        }
        return future.get()
    }

    @Test
    fun `our own controller may send well-formed custom commands`() {
        val c = connect()

        assertEquals(SessionResult.RESULT_SUCCESS, send(c, PlaybackCommands.CANCEL_SLEEP_TIMER).resultCode)
        assertEquals(
            SessionResult.RESULT_SUCCESS,
            send(c, PlaybackCommands.SET_SPEED, bundleOf(PlaybackCommands.EXTRA_SPEED to 1.5f)).resultCode,
        )
        assertEquals(
            SessionResult.RESULT_SUCCESS,
            send(c, PlaybackCommands.SET_SKIP_SILENCE, bundleOf(PlaybackCommands.EXTRA_ENABLED to true)).resultCode,
        )
        assertEquals(
            SessionResult.RESULT_SUCCESS,
            send(c, PlaybackCommands.START_SLEEP_TIMER, bundleOf(PlaybackCommands.EXTRA_DURATION_MS to 60_000L)).resultCode,
        )
        assertEquals(
            SessionResult.RESULT_SUCCESS,
            send(c, PlaybackCommands.PLAY_EPISODE, bundleOf(PlaybackCommands.EXTRA_EPISODE_ID to 99L)).resultCode,
        )
    }

    @Test
    fun `malformed custom commands are rejected`() {
        val c = connect()

        assertEquals(SessionError.ERROR_BAD_VALUE, send(c, PlaybackCommands.PLAY_EPISODE).resultCode)
        assertEquals(SessionError.ERROR_BAD_VALUE, send(c, PlaybackCommands.SET_SPEED).resultCode)
        assertEquals(SessionError.ERROR_BAD_VALUE, send(c, PlaybackCommands.SET_SKIP_SILENCE).resultCode)
        assertEquals(SessionError.ERROR_BAD_VALUE, send(c, PlaybackCommands.START_SLEEP_TIMER).resultCode)
    }

    @Test
    fun `transport commands go through the playback manager`() {
        val c = connect()

        c.pause()
        c.stop()
        c.seekTo(5_000)
        c.seekForward()
        c.seekBack()
        c.play()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))

    @Test
    fun `controllers can always go to the previous episode`() {
        val c = connect()
        idle()

        assertTrue(c.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS))
        assertTrue(c.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
    }

    @Test
    fun `next is offered only while the queue has an episode, and follows the queue`() {
        val c = connect()
        idle()
        assertFalse(c.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))

        playQueue.enqueue(7)
        idle()
        assertTrue(c.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        assertTrue(c.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))

        playQueue.clear()
        idle()
        assertFalse(c.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
    }

    @Test
    fun `next and previous from a controller are accepted`() {
        val c = connect()
        c.seekToNext()
        c.seekToPrevious()
        idle()
    }

    @Test
    fun `swiping the app away stops an idle service`() {
        service.onTaskRemoved(null)
    }
}
