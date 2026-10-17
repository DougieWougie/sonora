// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.player

import org.sonora.core.media.PlaybackFailure
import org.sonora.core.media.PlaybackFailureReason

fun PlaybackFailure.userMessage(): String = when (reason) {
    PlaybackFailureReason.NETWORK -> "Couldn't reach the server. Check your connection and try again."
    PlaybackFailureReason.SERVER -> "The server wouldn't provide this episode" + (httpStatus?.let { " (HTTP $it)." } ?: ".")
    PlaybackFailureReason.UNSUPPORTED -> "Sonora can't play this episode's audio format."
    PlaybackFailureReason.INSECURE -> "Blocked an insecure (HTTP) connection. You can allow cleartext in Settings."
    PlaybackFailureReason.BAD_ADDRESS -> "This episode's address isn't one Sonora can stream from."
    PlaybackFailureReason.OTHER -> "Couldn't play this episode."
}

/** Whether trying the same episode again could plausibly work, as opposed to a format or address problem. */
val PlaybackFailure.canRetry: Boolean
    get() = reason == PlaybackFailureReason.NETWORK || reason == PlaybackFailureReason.SERVER || reason == PlaybackFailureReason.OTHER
