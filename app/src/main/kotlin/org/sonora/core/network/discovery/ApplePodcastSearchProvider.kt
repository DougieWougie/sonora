// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.network.discovery

import kotlinx.serialization.Serializable
import org.sonora.core.model.DirectoryPodcast
import retrofit2.http.GET
import retrofit2.http.Query

interface ApplePodcastApi {
    @GET("search?media=podcast&entity=podcast")
    suspend fun search(@Query("term") term: String): AppleSearchDto
}

@Serializable
data class AppleSearchDto(val results: List<AppleResultDto> = emptyList())

@Serializable
data class AppleResultDto(
    val collectionName: String? = null,
    val feedUrl: String? = null,
    val artworkUrl600: String? = null,
    val artistName: String? = null,
)

class ApplePodcastSearchProvider(private val api: ApplePodcastApi) : PodcastDirectoryProvider {
    override suspend fun search(query: String): List<DirectoryPodcast> {
        if (query.isBlank()) return emptyList()
        val response = directoryCall { api.search(query.trim()) }
        return response.results.mapNotNull {
            val feed = it.feedUrl?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            DirectoryPodcast(it.collectionName ?: feed, it.artistName, feed, it.artworkUrl600)
        }
    }
}
