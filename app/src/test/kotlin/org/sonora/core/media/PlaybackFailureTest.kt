// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.media

import androidx.media3.common.PlaybackException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PlaybackFailureTest {
    private fun reason(code: Int, status: Int? = null) = PlaybackFailure.fromErrorCode(code, status)

    @Test
    fun `connection problems are network failures`() {
        for (code in listOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        )) assertEquals(PlaybackFailure(PlaybackFailureReason.NETWORK), reason(code))
    }

    @Test
    fun `a bad http status is a server failure that keeps the status`() {
        assertEquals(
            PlaybackFailure(PlaybackFailureReason.SERVER, httpStatus = 404),
            reason(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 404),
        )
    }

    @Test
    fun `audio that cannot be parsed or decoded is unsupported`() {
        for (code in listOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        )) assertEquals(PlaybackFailure(PlaybackFailureReason.UNSUPPORTED), reason(code))
    }

    @Test
    fun `a blocked cleartext connection is insecure`() {
        assertEquals(
            PlaybackFailure(PlaybackFailureReason.INSECURE),
            reason(PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED),
        )
    }

    @Test
    fun `anything else is other`() {
        assertEquals(PlaybackFailure(PlaybackFailureReason.OTHER), reason(PlaybackException.ERROR_CODE_UNSPECIFIED))
    }
}
