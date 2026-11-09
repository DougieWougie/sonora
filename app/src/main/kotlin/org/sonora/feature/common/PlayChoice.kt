// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.common

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import org.sonora.core.media.PlayQueue
import org.sonora.core.media.PlaybackPort

/**
 * What tapping an episode in a list does. With an empty queue it simply plays. Otherwise the user is
 * asked whether it goes next or last, because the queue never holds the playing episode and starting
 * this one would silently reshuffle what they lined up.
 */
class PlayChoice(private val queue: PlayQueue, private val playback: PlaybackPort) {
    private val _pending = MutableStateFlow<Long?>(null)

    /** The episode waiting for the user to pick "play next" or "add to end". */
    val pending: StateFlow<Long?> = _pending

    suspend fun request(episodeId: Long) {
        if (queue.items.value.isEmpty() || playback.state.first().episodeId == episodeId) {
            playback.play(episodeId)
        } else {
            _pending.value = episodeId
        }
    }

    fun playNext() = resolve(queue::enqueueNext)

    fun addToEnd() = resolve(queue::enqueue)

    fun dismiss() { _pending.value = null }

    private fun resolve(action: (Long) -> Unit) {
        _pending.value?.let(action)
        _pending.value = null
    }
}
