// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.ui

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import org.sonora.feature.downloads.StorageUsage

/** Decimal units, to match the storage limit choices: `512 B`, `1.5 KB`, `48 MB`, `1.4 GB`. */
fun formatBytes(bytes: Long, locale: Locale = Locale.getDefault()): String {
    val symbols = DecimalFormatSymbols(locale)
    var value = bytes.coerceAtLeast(0).toDouble()
    var unit = 0
    // Step up while the figure would be shown as 1000 or more (999_999 B reads as 1 MB, not 1000 KB).
    while (unit < SIZE_UNITS.lastIndex && displayed(value, unit) >= 1000) {
        value /= 1000
        unit++
    }
    val pattern = if (unit > 0 && value < 10) "0.#" else "0"
    return "${DecimalFormat(pattern, symbols).format(value)} ${SIZE_UNITS[unit]}"
}

private val SIZE_UNITS = listOf("B", "KB", "MB", "GB", "TB")

private fun displayed(value: Double, unit: Int): Double =
    if (unit > 0 && value < 10) Math.round(value * 10) / 10.0 else Math.round(value).toDouble()

/** `1.4 GB of 5 GB · 12 episodes`; the limit is left out when there is none. */
fun formatUsageSummary(usage: StorageUsage, locale: Locale = Locale.getDefault()): String {
    if (usage.downloadedCount == 0) return "No downloads"
    val used = formatBytes(usage.totalBytes, locale)
    val size = if (usage.limitBytes > 0) "$used of ${formatBytes(usage.limitBytes, locale)}" else used
    val count = usage.downloadedCount
    return "$size · $count episode${if (count == 1) "" else "s"}"
}

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
