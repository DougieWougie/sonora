// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.media

import androidx.media3.common.PlaybackException

enum class PlaybackFailureReason { NETWORK, SERVER, UNSUPPORTED, INSECURE, BAD_ADDRESS, OTHER }

/** Why playback could not start or carry on, reduced to what the UI can usefully tell the listener. */
data class PlaybackFailure(val reason: PlaybackFailureReason, val httpStatus: Int? = null) {
    companion object {
        /** [httpStatus] is the status of a rejected response, when the player's error chain carries one. */
        fun fromErrorCode(errorCode: Int, httpStatus: Int? = null): PlaybackFailure = when (errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> PlaybackFailure(PlaybackFailureReason.NETWORK)
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> PlaybackFailure(PlaybackFailureReason.SERVER, httpStatus)
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> PlaybackFailure(PlaybackFailureReason.INSECURE)
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> PlaybackFailure(PlaybackFailureReason.UNSUPPORTED)
            else -> PlaybackFailure(PlaybackFailureReason.OTHER)
        }
    }
}

/** The episode's address is not one we are willing to stream from. */
class StreamRefusedException(message: String) : IllegalStateException(message)
