// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.network.discovery

import kotlinx.serialization.Serializable
import org.sonora.core.model.DirectoryPodcast
import retrofit2.http.GET
import retrofit2.http.Query

interface ApplePodcastApi {
    @GET("search?media=podcast&entity=podcast")
    suspend fun search(@Query("term") term: String): AppleSearchDto

    /** [ids] is comma-separated; [country] makes the store pick the feed variant listed there. */
    @GET("lookup?entity=podcast")
    suspend fun lookup(@Query("id", encoded = true) ids: String, @Query("country") country: String): AppleSearchDto
}

@Serializable
data class AppleSearchDto(val results: List<AppleResultDto> = emptyList())

@Serializable
data class AppleResultDto(
    val collectionId: Long? = null,
    val collectionName: String? = null,
    val feedUrl: String? = null,
    val artworkUrl600: String? = null,
    val artistName: String? = null,
    val primaryGenreName: String? = null,
    val trackExplicitness: String? = null,
    val contentAdvisoryRating: String? = null,
)

internal val AppleResultDto.isExplicit: Boolean
    get() = trackExplicitness.equals("explicit", ignoreCase = true) ||
        contentAdvisoryRating.equals("Explicit", ignoreCase = true)

class ApplePodcastSearchProvider(private val api: ApplePodcastApi) : PodcastDirectoryProvider {
    override suspend fun search(query: String): List<DirectoryPodcast> {
        if (query.isBlank()) return emptyList()
        val response = directoryCall { api.search(query.trim()) }
        return response.results.mapNotNull {
            val feed = it.feedUrl?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            DirectoryPodcast(
                it.collectionName ?: feed, it.artistName, feed, it.artworkUrl600,
                category = it.primaryGenreName?.takeIf(String::isNotBlank), explicit = it.isExplicit,
            )
        }
    }
}
