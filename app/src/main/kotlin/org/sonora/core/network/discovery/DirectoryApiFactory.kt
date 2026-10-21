// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.network.discovery

import java.time.Clock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.sonora.core.network.isTransportAllowed
import org.sonora.core.network.syncJson
import org.sonora.core.network.withTransportPolicy
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

object DirectoryApiFactory {
    const val APPLE_BASE_URL = "https://itunes.apple.com/"
    const val APPLE_CHARTS_BASE_URL = "https://rss.marketingtools.apple.com/"
    const val PODCAST_INDEX_BASE_URL = "https://api.podcastindex.org/"

    fun apple(
        baseUrl: String = APPLE_BASE_URL,
        client: OkHttpClient = OkHttpClient(),
        allowCleartext: Boolean = false,
    ): ApplePodcastApi = retrofit(baseUrl, client, allowCleartext).create(ApplePodcastApi::class.java)

    fun appleCharts(
        baseUrl: String = APPLE_CHARTS_BASE_URL,
        client: OkHttpClient = OkHttpClient(),
        allowCleartext: Boolean = false,
    ): ApplePodcastChartsApi = retrofit(baseUrl, client, allowCleartext).create(ApplePodcastChartsApi::class.java)

    fun podcastIndex(
        baseUrl: String = PODCAST_INDEX_BASE_URL,
        credentials: PodcastIndexCredentials,
        userAgent: String,
        clock: Clock = Clock.systemUTC(),
        client: OkHttpClient = OkHttpClient(),
        allowCleartext: Boolean = false,
    ): PodcastIndexApi {
        val signed = client.newBuilder()
            .addInterceptor(PodcastIndexAuthInterceptor(credentials, userAgent, clock))
            .build()
        return retrofit(baseUrl, signed, allowCleartext).create(PodcastIndexApi::class.java)
    }

    private fun retrofit(baseUrl: String, client: OkHttpClient, allowCleartext: Boolean): Retrofit {
        require(isTransportAllowed(baseUrl, allowCleartext)) { "Refusing non-HTTPS directory URL" }
        return Retrofit.Builder()
            .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
            .client(client.withTransportPolicy(allowCleartext))
            .addConverterFactory(syncJson.asConverterFactory("application/json".toMediaType()))
            .build()
    }
}
