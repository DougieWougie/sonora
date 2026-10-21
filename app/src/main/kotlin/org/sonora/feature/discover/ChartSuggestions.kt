// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.discover

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.sonora.core.model.DirectoryPodcast
import org.sonora.core.network.discovery.PodcastChartProvider

/** What Discover offers before the user has searched for anything. */
interface DirectorySuggestions {
    /** Whatever is already known, instantly and without the network. Empty if nothing is. */
    fun cached(): List<DirectoryPodcast> = emptyList()

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
        // Each chart is two round trips, so ask for them all at once.
        val answers = coroutineScope {
            slots.map { slot ->
                async {
                    try {
                        Result.success(charts.top(slot.country, slot.limit))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                }
            }.awaitAll()
        }
        if (answers.none { it.isSuccess }) {
            throw answers.firstOrNull()?.exceptionOrNull() ?: IllegalStateException("No charts configured")
        }
        return answers.flatMap { it.getOrDefault(emptyList()) }.distinctBy { it.feedUrl.trim().trimEnd('/').lowercase() }
    }

    companion object {
        /** UK first, with a smaller global tail so the list isn't only what the UK already knows. */
        val DEFAULT_SLOTS = listOf(ChartSlot("gb", 20), ChartSlot("us", 10))
    }
}
