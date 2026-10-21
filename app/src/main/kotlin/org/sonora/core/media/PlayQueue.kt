// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.media

import kotlinx.coroutines.flow.StateFlow

/** The user's "up next" list of episode ids, in play order. */
interface PlayQueue {
    val items: StateFlow<List<Long>>
    fun enqueue(episodeId: Long)
    /** Puts the episode at the head, moving it there if it is already queued. */
    fun enqueueNext(episodeId: Long)
    /** Moves a queued episode to [toIndex] (clamped to the queue); does nothing if it is not queued. */
    fun move(episodeId: Long, toIndex: Int)
    fun remove(episodeId: Long)
    /** Sets a new order wholesale; duplicates keep their first place. */
    fun replaceAll(episodeIds: List<Long>)
    fun clear()

    /** Removes and returns the first item, if any. */
    fun pop(): Long?
}

/** Where a [PlayQueue] keeps its items between launches. */
interface QueueStorage {
    fun load(): List<Long>
    fun save(items: List<Long>)
}
