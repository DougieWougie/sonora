// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.common

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.sonora.core.media.FakeQueueStorage
import org.sonora.core.media.PersistentPlayQueue
import org.sonora.testutil.FakePlaybackPort

class PlayChoiceTest {
    private val queue = PersistentPlayQueue(FakeQueueStorage())
    private val playback = FakePlaybackPort()
    private val choice = PlayChoice(queue, playback)

    @Test
    fun `with an empty queue the episode just plays`() = runTest {
        choice.request(7)

        assertEquals(listOf("play:7"), playback.calls)
        assertNull(choice.pending.value)
    }

    @Test
    fun `with a queue the user is asked and nothing plays yet`() = runTest {
        queue.enqueue(1)

        choice.request(7)

        assertEquals(7L, choice.pending.value)
        assertEquals(emptyList<String>(), playback.calls)
        assertEquals(listOf(1L), queue.items.value)
    }

    @Test
    fun `play next puts the episode at the head of the queue`() = runTest {
        queue.enqueue(1)
        choice.request(7)

        choice.playNext()

        assertEquals(listOf(7L, 1L), queue.items.value)
        assertNull(choice.pending.value)
        assertEquals(emptyList<String>(), playback.calls)
    }

    @Test
    fun `add to end puts the episode last in the queue`() = runTest {
        queue.enqueue(1)
        choice.request(7)

        choice.addToEnd()

        assertEquals(listOf(1L, 7L), queue.items.value)
        assertNull(choice.pending.value)
    }

    @Test
    fun `dismissing changes nothing`() = runTest {
        queue.enqueue(1)
        choice.request(7)

        choice.dismiss()

        assertNull(choice.pending.value)
        assertEquals(listOf(1L), queue.items.value)
    }

    @Test
    fun `the episode already playing is not queued behind itself`() = runTest {
        queue.enqueue(1)
        playback.play(7)
        playback.calls.clear()

        choice.request(7)

        assertEquals(listOf("play:7"), playback.calls)
        assertNull(choice.pending.value)
    }
}
