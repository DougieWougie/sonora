// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.media

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class ExoPlayerEngineTest {
    private val player = mockk<ExoPlayer>(relaxed = true)
    private val engine = ExoPlayerEngine(player)

    @Test
    fun `it reports the player's position and buffer`() {
        every { player.currentPosition } returns 1_500
        every { player.bufferedPosition } returns 9_000

        assertEquals(1_500L, engine.positionMs)
        assertEquals(9_000L, engine.bufferedMs)
    }

    @Test
    fun `a known duration is reported`() {
        every { player.duration } returns 60_000

        assertEquals(60_000L, engine.durationMs)
    }

    @Test
    fun `an unknown duration is reported as zero`() {
        every { player.duration } returns C.TIME_UNSET

        assertEquals(0L, engine.durationMs)
    }

    @Test
    fun `prepare loads the item at the start position and prepares`() {
        val item = slot<MediaItem>()
        every { player.setMediaItem(capture(item), any<Long>()) } just Runs

        engine.prepare("https://a/ep.mp3", 4_000, PlaybackMetadata("7", "The Episode", "The Podcast", "https://a/art.jpg"))

        verifyOrder {
            player.setMediaItem(any<MediaItem>(), 4_000L)
            player.prepare()
        }
        assertEquals("7", item.captured.mediaId)
        assertEquals("https://a/ep.mp3", item.captured.localConfiguration!!.uri.toString())
        assertEquals("The Episode", item.captured.mediaMetadata.title.toString())
        assertEquals("The Podcast", item.captured.mediaMetadata.artist.toString())
        assertEquals("https://a/art.jpg", item.captured.mediaMetadata.artworkUri.toString())
    }

    @Test
    fun `prepare tolerates missing artwork and artist`() {
        val item = slot<MediaItem>()
        every { player.setMediaItem(capture(item), any<Long>()) } just Runs

        engine.prepare("https://a/ep.mp3", 0, PlaybackMetadata("7", "The Episode", null, null))

        assertNull(item.captured.mediaMetadata.artworkUri)
        assertNull(item.captured.mediaMetadata.artist)
    }

    @Test
    fun `play, pause and seek pass straight through`() {
        engine.play()
        engine.pause()
        engine.seekTo(2_000)

        verify {
            player.play()
            player.pause()
            player.seekTo(2_000L)
        }
    }

    @Test
    fun `stop stops and clears the queue`() {
        engine.stop()

        verifyOrder {
            player.stop()
            player.clearMediaItems()
        }
    }

    @Test
    fun `speed and skip silence are applied`() {
        engine.setSpeed(1.5f)
        engine.setSkipSilence(true)

        verify {
            player.setPlaybackParameters(PlaybackParameters(1.5f))
            player.skipSilenceEnabled = true
        }
    }
}
