// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.ui

/** `m:ss`, or `h:mm:ss` from an hour up. [roundUp] is for countdowns, which should read "0:01" until they hit zero. */
fun formatTimestamp(millis: Long, roundUp: Boolean = false): String {
    val totalSeconds = (if (roundUp) (millis + 999) / 1000 else millis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = totalSeconds % 3600 / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

/** `-m:ss` left in the episode, or a placeholder while the duration is unknown. */
fun formatRemaining(positionMs: Long, durationMs: Long): String =
    if (durationMs <= 0) "--:--" else "-" + formatTimestamp((durationMs - positionMs).coerceAtLeast(0))
