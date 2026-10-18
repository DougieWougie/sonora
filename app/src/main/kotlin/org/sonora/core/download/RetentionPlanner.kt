// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.download

/** [keepPerPodcast] and [maxBytes] are both "0 means unlimited". */
data class RetentionPolicy(val keepPerPodcast: Int = 0, val maxBytes: Long = 0)

/** [inUse] marks an episode that must survive eviction, such as the one currently playing. */
data class DownloadedEpisode(
    val id: Long,
    val podcastId: Long,
    val publishedAtMillis: Long?,
    val played: Boolean,
    val sizeBytes: Long,
    val inUse: Boolean = false,
)

/**
 * Decides which downloads to delete. Within either rule, played episodes go before unplayed ones and
 * older before newer, so unplayed episodes are only touched when nothing else would satisfy the policy.
 * Episodes that are [DownloadedEpisode.inUse] are never chosen.
 */
object RetentionPlanner {
    private val evictionOrder = compareByDescending<DownloadedEpisode> { it.played }
        .thenBy { it.publishedAtMillis ?: Long.MIN_VALUE }

    fun plan(downloads: List<DownloadedEpisode>, policy: RetentionPolicy): List<Long> {
        val evicted = LinkedHashSet<Long>()

        if (policy.keepPerPodcast > 0) {
            for (podcast in downloads.groupBy { it.podcastId }.values) {
                val excess = podcast.size - policy.keepPerPodcast
                if (excess > 0) {
                    podcast.filterNot { it.inUse }.sortedWith(evictionOrder).take(excess).mapTo(evicted) { it.id }
                }
            }
        }

        if (policy.maxBytes > 0) {
            val remaining = downloads.filter { it.id !in evicted }
            var total = remaining.sumOf { it.sizeBytes }
            for (episode in remaining.filterNot { it.inUse }.sortedWith(evictionOrder)) {
                if (total <= policy.maxBytes) break
                total -= episode.sizeBytes
                evicted += episode.id
            }
        }
        return evicted.toList()
    }
}
