// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.work

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DownloadNotificationsTest {
    @Test
    fun `concurrent downloads get different notification ids`() {
        val ids = (1L..50L).map { DownloadNotifications.idFor(it) }

        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `an episode always maps to the same id`() {
        assertEquals(DownloadNotifications.idFor(42), DownloadNotifications.idFor(42))
    }

    @Test
    fun `ids stay positive for any episode id`() {
        listOf(0L, 1L, Long.MAX_VALUE, Int.MAX_VALUE.toLong() + 1).forEach {
            assertTrue(DownloadNotifications.idFor(it) > 0, "id for $it")
        }
    }

    @Test
    fun `ids do not collide with the media playback notification`() {
        val mediaNotificationId = 1001 // Media3's DefaultMediaNotificationProvider
        (0L..5000L).forEach { assertNotEquals(mediaNotificationId, DownloadNotifications.idFor(it)) }
    }
}
