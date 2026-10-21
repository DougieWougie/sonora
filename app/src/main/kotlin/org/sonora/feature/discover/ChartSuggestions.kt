// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.discover

import kotlinx.coroutines.CancellationException
import org.sonora.core.model.DirectoryPodcast
import org.sonora.core.network.discovery.PodcastChartProvider

/** What Discover offers before the user has searched for anything. */
fun interface DirectorySuggestions {
    suspend fun suggestions(): List<DirectoryPodcast>
}

/** How many shows to take from one country's chart. */
data class ChartSlot(val country: String, val limit: Int)

/**
 * Suggests chart-topping shows, in [slots] order so earlier countries are favoured. A chart that
 * fails is skipped as long as another answers; if all fail, the first failure is thrown.
 */
class ChartSuggestions(
    private val charts: PodcastChartProvider,
    private val slots: List<ChartSlot> = DEFAULT_SLOTS,
) : DirectorySuggestions {
    override suspend fun suggestions(): List<DirectoryPodcast> {
        var firstFailure: Exception? = null
        var succeeded = false
        val all = mutableListOf<DirectoryPodcast>()
        for (slot in slots) {
            try {
                all += charts.top(slot.country, slot.limit)
                succeeded = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                firstFailure = firstFailure ?: e
            }
        }
        if (!succeeded) throw firstFailure ?: IllegalStateException("No charts configured")
        return all.distinctBy { it.feedUrl.trim().trimEnd('/').lowercase() }
    }

    companion object {
        /** UK first, with a smaller global tail so the list isn't only what the UK already knows. */
        val DEFAULT_SLOTS = listOf(ChartSlot("gb", 20), ChartSlot("us", 10))
    }
}
