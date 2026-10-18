// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FormatTest {
    @Test
    fun `formats minutes and seconds`() {
        assertEquals("0:00", formatTimestamp(0))
        assertEquals("0:09", formatTimestamp(9_999))
        assertEquals("1:05", formatTimestamp(65_000))
        assertEquals("59:59", formatTimestamp(3_599_000))
    }

    @Test
    fun `adds hours only when needed`() {
        assertEquals("1:00:00", formatTimestamp(3_600_000))
        assertEquals("1:02:05", formatTimestamp(3_725_000))
        assertEquals("10:00:00", formatTimestamp(36_000_000))
    }

    @Test
    fun `never shows negative time`() {
        assertEquals("0:00", formatTimestamp(-5_000))
    }

    @Test
    fun `remaining time is shown with a minus sign`() {
        assertEquals("-2:30", formatRemaining(positionMs = 30_000, durationMs = 180_000))
        assertEquals("-0:00", formatRemaining(positionMs = 200_000, durationMs = 180_000))
        assertEquals("--:--", formatRemaining(positionMs = 1_000, durationMs = 0))
    }

    @Test
    fun `sleep timer shows the time left, rounding up to the next second`() {
        assertEquals("15:00", formatTimestamp(14 * 60_000L + 59_001, roundUp = true))
        assertEquals("0:01", formatTimestamp(1, roundUp = true))
    }

    // --- sizes -------------------------------------------------------------------------------

    private fun bytes(n: Long) = formatBytes(n, java.util.Locale.US)

    @Test
    fun `small sizes are shown in bytes`() {
        assertEquals("0 B", bytes(0))
        assertEquals("999 B", bytes(999))
    }

    @Test
    fun `sizes under ten of a unit keep one decimal, larger ones are whole`() {
        assertEquals("1 KB", bytes(1_000))
        assertEquals("1.5 KB", bytes(1_500))
        assertEquals("48 KB", bytes(48_000))
        assertEquals("48 MB", bytes(48_000_000))
        assertEquals("1.4 GB", bytes(1_400_000_000))
        assertEquals("12 GB", bytes(12_345_000_000))
    }

    @Test
    fun `a size that rounds up to the next unit is shown in it`() {
        assertEquals("1 MB", bytes(999_999))
    }

    @Test
    fun `negative sizes read as zero`() {
        assertEquals("0 B", bytes(-5))
    }

    @Test
    fun `sizes follow the locale's decimal separator`() {
        assertEquals("1,5 KB", formatBytes(1_500, java.util.Locale.GERMANY))
    }

    private fun summary(usage: org.sonora.feature.downloads.StorageUsage) = formatUsageSummary(usage, java.util.Locale.US)

    @Test
    fun `usage summary says when nothing is downloaded`() {
        assertEquals("No downloads", summary(org.sonora.feature.downloads.StorageUsage()))
    }

    @Test
    fun `usage summary shows the total and episode count`() {
        val usage = org.sonora.feature.downloads.StorageUsage(totalBytes = 1_400_000_000, downloadedCount = 12)
        assertEquals("1.4 GB · 12 episodes", summary(usage))
    }

    @Test
    fun `usage summary includes the limit when there is one`() {
        val usage = org.sonora.feature.downloads.StorageUsage(totalBytes = 1_400_000_000, limitBytes = 5_000_000_000, downloadedCount = 1)
        assertEquals("1.4 GB of 5 GB · 1 episode", summary(usage))
    }
}
