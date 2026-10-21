// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.media

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class FakeQueueStorage(var stored: List<Long> = emptyList()) : QueueStorage {
    override fun load() = stored
    override fun save(items: List<Long>) { stored = items }
}

class PersistentPlayQueueTest {
    private val storage = FakeQueueStorage()
    private val queue = PersistentPlayQueue(storage)

    @Test
    fun `loads what was stored`() {
        assertEquals(listOf(3L, 1L), PersistentPlayQueue(FakeQueueStorage(listOf(3, 1))).items.value)
    }

    @Test
    fun `enqueue appends and persists`() {
        queue.enqueue(1)
        queue.enqueue(2)
        assertEquals(listOf(1L, 2L), queue.items.value)
        assertEquals(listOf(1L, 2L), storage.stored)
    }

    @Test
    fun `enqueueing an episode twice keeps its first place only`() {
        queue.enqueue(1)
        queue.enqueue(2)
        queue.enqueue(1)
        assertEquals(listOf(1L, 2L), queue.items.value)
    }

    @Test
    fun `remove drops one item and persists`() {
        listOf(1L, 2L, 3L).forEach(queue::enqueue)
        queue.remove(2)
        assertEquals(listOf(1L, 3L), storage.stored)
    }

    @Test
    fun `pop takes the head and returns null when empty`() {
        queue.enqueue(7)
        queue.enqueue(8)
        assertEquals(7L, queue.pop())
        assertEquals(listOf(8L), storage.stored)
        queue.pop()
        assertNull(queue.pop())
    }

    @Test
    fun `clear empties and persists`() {
        queue.enqueue(1)
        queue.clear()
        assertEquals(emptyList<Long>(), queue.items.value)
        assertEquals(emptyList<Long>(), storage.stored)
    }

    @Test
    fun `enqueueNext puts an episode at the head, moving it if already queued`() {
        listOf(1L, 2L, 3L).forEach(queue::enqueue)
        queue.enqueueNext(3)
        assertEquals(listOf(3L, 1L, 2L), queue.items.value)
        queue.enqueueNext(9)
        assertEquals(listOf(9L, 3L, 1L, 2L), storage.stored)
    }

    @Test
    fun `replaceAll sets a new order without duplicates and persists`() {
        queue.enqueue(1)
        queue.replaceAll(listOf(4, 2, 4, 3))
        assertEquals(listOf(4L, 2L, 3L), queue.items.value)
        assertEquals(listOf(4L, 2L, 3L), storage.stored)
    }
}
