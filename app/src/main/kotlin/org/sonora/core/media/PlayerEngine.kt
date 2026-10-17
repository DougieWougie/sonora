// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.media

/** What the system UI (notification, lock screen) shows for the current item. */
data class PlaybackMetadata(
    val mediaId: String,
    val title: String,
    val artist: String?,
    val artworkUrl: String?,
)

/**
 * The few player operations the playback rules depend on, so [PlaybackStateManager] can be tested
 * on the JVM. All methods are called from the thread that owns the underlying player.
 */
interface PlayerEngine {
    val positionMs: Long
    val durationMs: Long
    val bufferedMs: Long

    fun prepare(uri: String, startPositionMs: Long, metadata: PlaybackMetadata)
    fun play()
    fun pause()
    fun stop()
    fun seekTo(positionMs: Long)
    fun setSpeed(speed: Float)
    fun setSkipSilence(enabled: Boolean)
}
