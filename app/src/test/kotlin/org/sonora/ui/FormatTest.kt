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
}
