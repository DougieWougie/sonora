// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.media

import kotlinx.coroutines.flow.Flow

data class PlaybackProgress(val positionMs: Long, val bufferedMs: Long, val durationMs: Long) {
    companion object {
        val None = PlaybackProgress(0, 0, 0)
    }
}

/** What the UI layer needs from playback, whichever process-local object actually owns the player. */
interface PlaybackPort {
    val state: Flow<PlaybackState>
    /** One-off failures to tell the listener about; not replayed to late collectors. */
    val errors: Flow<PlaybackFailure>
    fun progress(): Flow<PlaybackProgress>

    suspend fun play(episodeId: Long)
    suspend fun pause()
    suspend fun seekTo(positionMs: Long)
    /** Starts the head of the queue; does nothing when it is empty. */
    suspend fun next()
    /** Restarts the episode if it is well under way, otherwise goes back to the one played before it. */
    suspend fun previous()
    fun setSpeed(speed: Float)
    fun setSkipSilence(enabled: Boolean)
    fun startSleepTimer(durationMs: Long)
    fun cancelSleepTimer()
}
