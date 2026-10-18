// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.download

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RetentionPlannerTest {
    private fun download(
        id: Long,
        podcast: Long = 1,
        published: Long = id,
        played: Boolean = false,
        size: Long = 1000,
        inUse: Boolean = false,
    ) = DownloadedEpisode(id, podcast, published, played, size, inUse)

    private fun plan(policy: RetentionPolicy, vararg downloads: DownloadedEpisode) =
        RetentionPlanner.plan(downloads.toList(), policy)

    @Test
    fun `an unlimited policy evicts nothing`() {
        assertEquals(emptyList<Long>(), plan(RetentionPolicy(), download(1), download(2, played = true)))
    }

    @Test
    fun `keepPerPodcast keeps the newest downloads of each podcast`() {
        val evicted = plan(
            RetentionPolicy(keepPerPodcast = 2),
            download(1, podcast = 1, published = 10),
            download(2, podcast = 1, published = 30),
            download(3, podcast = 1, published = 20),
            download(4, podcast = 2, published = 5),
        )

        assertEquals(listOf(1L), evicted)
    }

    @Test
    fun `keepPerPodcast evicts played episodes before unplayed ones`() {
        val evicted = plan(
            RetentionPolicy(keepPerPodcast = 1),
            download(1, published = 10, played = false),
            download(2, published = 20, played = true),
            download(3, published = 30, played = false),
        )

        // Two must go: the played one first, then the oldest unplayed.
        assertEquals(listOf(2L, 1L), evicted)
    }

    @Test
    fun `the storage cap evicts oldest played downloads first`() {
        val evicted = plan(
            RetentionPolicy(maxBytes = 2000),
            download(1, published = 1, played = true),
            download(2, published = 3, played = true),
            download(3, published = 0, played = false),
        )

        assertEquals(listOf(1L), evicted)
    }

    @Test
    fun `the storage cap falls back to the oldest unplayed downloads as a last resort`() {
        val evicted = plan(
            RetentionPolicy(maxBytes = 1000),
            download(1, published = 1, played = true),
            download(2, published = 5, played = false),
            download(3, published = 2, played = false),
        )

        assertEquals(listOf(1L, 3L), evicted)
    }

    @Test
    fun `the storage cap stops as soon as the total fits`() {
        val evicted = plan(
            RetentionPolicy(maxBytes = 2500),
            download(1, published = 1, played = true, size = 1000),
            download(2, published = 2, played = true, size = 1000),
            download(3, published = 3, played = true, size = 1000),
        )

        assertEquals(listOf(1L), evicted)
    }

    @Test
    fun `episodes in use are never evicted`() {
        val evicted = plan(
            RetentionPolicy(keepPerPodcast = 1, maxBytes = 1),
            download(1, published = 1, played = true, inUse = true),
            download(2, published = 2),
        )

        assertEquals(listOf(2L), evicted)
    }

    @Test
    fun `episodes evicted by the per-podcast rule count towards the storage cap`() {
        val evicted = plan(
            RetentionPolicy(keepPerPodcast = 1, maxBytes = 1000),
            download(1, podcast = 1, published = 1),
            download(2, podcast = 1, published = 2),
            download(3, podcast = 2, published = 3),
        )

        // 1 goes for the per-podcast rule, which leaves 2 and 3 over the cap; 2 is older than 3.
        assertEquals(listOf(1L, 2L), evicted)
    }

    @Test
    fun `episodes without a publish date are treated as the oldest`() {
        val evicted = plan(
            RetentionPolicy(keepPerPodcast = 1),
            download(1, published = 0).copy(publishedAtMillis = null),
            download(2, published = 50),
        )

        assertEquals(listOf(1L), evicted)
    }
}
