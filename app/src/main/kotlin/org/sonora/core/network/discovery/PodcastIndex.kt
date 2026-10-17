// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.network.discovery

import java.security.MessageDigest
import java.time.Clock
import kotlinx.serialization.Serializable
import okhttp3.Interceptor
import okhttp3.Response
import org.sonora.core.model.DirectoryPodcast
import retrofit2.http.GET
import retrofit2.http.Query

/** Signs every request as described at https://podcastindex-org.github.io/docs-api/#auth. */
class PodcastIndexAuthInterceptor(
    private val credentials: PodcastIndexCredentials,
    private val userAgent: String,
    private val clock: Clock,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val epochSeconds = clock.millis() / 1000
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("${credentials.apiKey}${credentials.apiSecret}$epochSeconds".toByteArray())
        return chain.proceed(
            chain.request().newBuilder()
                .header("User-Agent", userAgent)
                .header("X-Auth-Key", credentials.apiKey)
                .header("X-Auth-Date", epochSeconds.toString())
                .header("Authorization", digest.joinToString("") { "%02x".format(it) })
                .build(),
        )
    }
}

interface PodcastIndexApi {
    @GET("api/1.0/search/byterm")
    suspend fun searchByTerm(@Query("q") query: String): PodcastIndexSearchDto
}

@Serializable
data class PodcastIndexSearchDto(val feeds: List<PodcastIndexFeedDto> = emptyList())

@Serializable
data class PodcastIndexFeedDto(
    val title: String? = null,
    val url: String? = null,
    val author: String? = null,
    val image: String? = null,
    val artwork: String? = null,
)

class PodcastIndexSearchProvider(private val api: PodcastIndexApi) : PodcastDirectoryProvider {
    override suspend fun search(query: String): List<DirectoryPodcast> {
        if (query.isBlank()) return emptyList()
        val response = directoryCall { api.searchByTerm(query.trim()) }
        return response.feeds.mapNotNull {
            val feed = it.url?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            DirectoryPodcast(
                title = it.title ?: feed,
                author = it.author?.takeIf(String::isNotBlank),
                feedUrl = feed,
                artworkUrl = it.artwork?.takeIf(String::isNotBlank) ?: it.image?.takeIf(String::isNotBlank),
            )
        }
    }
}
