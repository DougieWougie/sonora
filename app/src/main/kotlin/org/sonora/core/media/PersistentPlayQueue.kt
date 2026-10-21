// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.media

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

class PersistentPlayQueue(private val storage: QueueStorage) : PlayQueue {
    private val _items = MutableStateFlow(storage.load())
    override val items: StateFlow<List<Long>> = _items

    override fun enqueue(episodeId: Long) = change { if (episodeId in it) it else it + episodeId }
    override fun enqueueNext(episodeId: Long) = change { listOf(episodeId) + (it - episodeId) }
    override fun replaceAll(episodeIds: List<Long>) = change { episodeIds.distinct() }
    override fun move(episodeId: Long, toIndex: Int) = change {
        if (episodeId !in it) return@change it
        val rest = it - episodeId
        rest.take(toIndex.coerceIn(0, rest.size)) + episodeId + rest.drop(toIndex.coerceIn(0, rest.size))
    }
    override fun remove(episodeId: Long) = change { it - episodeId }
    override fun clear() = change { emptyList() }

    override fun pop(): Long? {
        val head = _items.value.firstOrNull() ?: return null
        change { it.drop(1) }
        return head
    }

    private fun change(transform: (List<Long>) -> List<Long>) {
        _items.update(transform)
        storage.save(_items.value)
    }
}

/** Keeps the queue as a comma-separated list in SharedPreferences. */
class PrefsQueueStorage(context: Context) : QueueStorage {
    private val prefs = context.applicationContext.getSharedPreferences("queue", Context.MODE_PRIVATE)

    override fun load(): List<Long> =
        prefs.getString(KEY, "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }

    override fun save(items: List<Long>) {
        prefs.edit().putString(KEY, items.joinToString(",")).apply()
    }

    private companion object {
        const val KEY = "items"
    }
}
