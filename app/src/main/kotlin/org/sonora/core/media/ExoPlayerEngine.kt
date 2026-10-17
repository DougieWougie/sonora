// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/** [PlayerEngine] backed by the real player. Must be used on the player's application thread. */
@UnstableApi
class ExoPlayerEngine(private val player: ExoPlayer) : PlayerEngine {
    override val positionMs: Long get() = player.currentPosition
    override val durationMs: Long get() = player.duration.takeIf { it != C.TIME_UNSET } ?: 0

    override val bufferedMs: Long get() = player.bufferedPosition

    override fun prepare(uri: String, startPositionMs: Long, metadata: PlaybackMetadata) {
        val item = MediaItem.Builder()
            .setMediaId(metadata.mediaId)
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(metadata.title)
                    .setArtist(metadata.artist)
                    .setArtworkUri(metadata.artworkUrl?.let(Uri::parse))
                    .build(),
            )
            .build()
        player.setMediaItem(item, startPositionMs)
        player.prepare()
    }

    override fun play() = player.play()
    override fun pause() = player.pause()
    override fun stop() {
        player.stop()
        player.clearMediaItems()
    }

    override fun seekTo(positionMs: Long) = player.seekTo(positionMs)
    override fun setSpeed(speed: Float) = player.setPlaybackParameters(PlaybackParameters(speed))
    override fun setSkipSilence(enabled: Boolean) {
        player.skipSilenceEnabled = enabled
    }
}
