// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.network.discovery

import kotlinx.serialization.Serializable
import org.sonora.core.model.DirectoryPodcast
import retrofit2.http.GET
import retrofit2.http.Path

interface ApplePodcastChartsApi {
    @GET("api/v2/{country}/podcasts/top/{limit}/podcasts.json")
    suspend fun top(@Path("country") country: String, @Path("limit") limit: Int): AppleChartDto
}

@Serializable
data class AppleChartDto(val feed: AppleChartFeedDto = AppleChartFeedDto())

@Serializable
data class AppleChartFeedDto(val results: List<AppleChartEntryDto> = emptyList())

@Serializable
data class AppleChartEntryDto(
    val id: String,
    val name: String? = null,
    val artistName: String? = null,
    val artworkUrl100: String? = null,
)

/** A country's most popular shows. */
interface PodcastChartProvider {
    /** Never returns shows without a feed URL, and keeps the chart's order. */
    suspend fun top(country: String, limit: Int): List<DirectoryPodcast>
}

/**
 * Apple's per-country top podcasts. The chart only names shows, so a second lookup finds their feeds.
 * Neither call needs an API key.
 */
class ApplePodcastChartProvider(
    private val charts: ApplePodcastChartsApi,
    private val lookup: ApplePodcastApi,
) : PodcastChartProvider {
    override suspend fun top(country: String, limit: Int): List<DirectoryPodcast> {
        val chart = directoryCall { charts.top(country, limit) }.feed.results
        if (chart.isEmpty()) return emptyList()

        val found = directoryCall { lookup.lookup(chart.joinToString(",") { it.id }, country) }.results
            .associateBy { it.collectionId?.toString() }
        return chart.mapNotNull { entry ->
            val match = found[entry.id]
            val feed = match?.feedUrl?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            DirectoryPodcast(
                title = match.collectionName ?: entry.name ?: feed,
                author = match.artistName ?: entry.artistName,
                feedUrl = feed,
                artworkUrl = match.artworkUrl600 ?: entry.artworkUrl100,
            )
        }
    }
}
