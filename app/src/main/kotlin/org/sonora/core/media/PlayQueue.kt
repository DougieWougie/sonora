// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.media

import kotlinx.coroutines.flow.StateFlow

/** The user's "up next" list of episode ids, in play order. */
interface PlayQueue {
    val items: StateFlow<List<Long>>
    fun enqueue(episodeId: Long)
    fun remove(episodeId: Long)
    fun clear()

    /** Removes and returns the first item, if any. */
    fun pop(): Long?
}

/** Where a [PlayQueue] keeps its items between launches. */
interface QueueStorage {
    fun load(): List<Long>
    fun save(items: List<Long>)
}
