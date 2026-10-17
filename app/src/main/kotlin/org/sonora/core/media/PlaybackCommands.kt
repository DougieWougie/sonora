// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.media

import android.os.Bundle
import androidx.media3.session.SessionCommand

/** Custom [SessionCommand]s a `MediaController` uses to drive [PlaybackStateManager] features. */
object PlaybackCommands {
    const val EXTRA_EPISODE_ID = "episodeId"
    const val EXTRA_SPEED = "speed"
    const val EXTRA_ENABLED = "enabled"
    const val EXTRA_DURATION_MS = "durationMs"

    val PLAY_EPISODE = SessionCommand("org.sonora.PLAY_EPISODE", Bundle.EMPTY)
    val SET_SPEED = SessionCommand("org.sonora.SET_SPEED", Bundle.EMPTY)
    val SET_SKIP_SILENCE = SessionCommand("org.sonora.SET_SKIP_SILENCE", Bundle.EMPTY)
    val START_SLEEP_TIMER = SessionCommand("org.sonora.START_SLEEP_TIMER", Bundle.EMPTY)
    val CANCEL_SLEEP_TIMER = SessionCommand("org.sonora.CANCEL_SLEEP_TIMER", Bundle.EMPTY)

    val ALL = listOf(PLAY_EPISODE, SET_SPEED, SET_SKIP_SILENCE, START_SLEEP_TIMER, CANCEL_SLEEP_TIMER)
}
