// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.database

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SubscriptionRepositoryTest {
    private val podcastDao = FakePodcastDao()
    private val changeDao = FakeSubscriptionChangeDao()
    private val clock = MutableClock()
    private val repo = SubscriptionRepository(podcastDao, changeDao, FakeTransactionRunner(), clock)

    @Test
    fun `subscribe creates a placeholder podcast and records an add`() = runTest {
        repo.subscribe("https://f/feed", "Show")

        assertEquals("Show", podcastDao.findByFeedUrl("https://f/feed")!!.title)
        assertEquals(SubscriptionChangeEntity("https://f/feed", true, clock.nowMillis), changeDao.rows["https://f/feed"])
    }

    @Test
    fun `subscribing twice keeps a single podcast`() = runTest {
        repo.subscribe("https://f/feed")
        repo.subscribe("https://f/feed")
        assertEquals(1, podcastDao.getAll().size)
    }

    @Test
    fun `unsubscribe removes the podcast and records a removal`() = runTest {
        repo.subscribe("https://f/feed")
        clock.nowMillis += 10

        repo.unsubscribe("https://f/feed")

        assertNull(podcastDao.findByFeedUrl("https://f/feed"))
        assertEquals(false, changeDao.rows["https://f/feed"]!!.subscribed)
        assertEquals(1, changeDao.rows.size)
    }
}
