// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.sonora.core.media.PlaybackFailure
import org.sonora.core.media.PlaybackFailureReason

class PlaybackFailureMessageTest {
    private fun message(reason: PlaybackFailureReason, status: Int? = null) = PlaybackFailure(reason, status).userMessage()

    @Test
    fun `network failures suggest checking the connection`() {
        assertEquals("Couldn't reach the server. Check your connection and try again.", message(PlaybackFailureReason.NETWORK))
    }

    @Test
    fun `server failures include the http status when known`() {
        assertEquals("The server wouldn't provide this episode (HTTP 404).", message(PlaybackFailureReason.SERVER, 404))
        assertEquals("The server wouldn't provide this episode.", message(PlaybackFailureReason.SERVER))
    }

    @Test
    fun `insecure connections point at the cleartext setting`() {
        assertTrue(message(PlaybackFailureReason.INSECURE).contains("Settings"))
    }

    @Test
    fun `every reason has a distinct message`() {
        val messages = PlaybackFailureReason.entries.map { message(it) }
        assertEquals(messages.size, messages.toSet().size)
    }

    @Test
    fun `only transient failures offer a retry`() {
        val retryable = PlaybackFailureReason.entries.filter { PlaybackFailure(it).canRetry }
        assertEquals(
            listOf(PlaybackFailureReason.NETWORK, PlaybackFailureReason.SERVER, PlaybackFailureReason.OTHER),
            retryable,
        )
    }
}
