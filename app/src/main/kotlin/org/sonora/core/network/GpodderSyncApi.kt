// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.network

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/** Nextcloud `gpoddersync` app endpoints. Paths are relative to the Nextcloud base URL. */
interface GpodderSyncApi {
    @GET("index.php/apps/gpoddersync/subscriptions")
    suspend fun subscriptions(@Query("since") since: Long): SubscriptionChangesDto

    @POST("index.php/apps/gpoddersync/subscription_change/create")
    suspend fun pushSubscriptions(@Body body: SubscriptionPushDto): TimestampDto

    @GET("index.php/apps/gpoddersync/episode_action")
    suspend fun episodeActions(@Query("since") since: Long): EpisodeActionsDto

    @POST("index.php/apps/gpoddersync/episode_action/create")
    suspend fun pushEpisodeActions(@Body body: List<EpisodeActionDto>): TimestampDto
}

@Serializable
data class SubscriptionChangesDto(
    val add: List<String> = emptyList(),
    val remove: List<String> = emptyList(),
    val timestamp: Long = 0,
)

@Serializable
data class SubscriptionPushDto(val add: List<String>, val remove: List<String>)

@Serializable
data class TimestampDto(val timestamp: Long = 0)

@Serializable
data class EpisodeActionsDto(val actions: List<EpisodeActionDto> = emptyList(), val timestamp: Long = 0)

@Serializable
data class EpisodeActionDto(
    val podcast: String,
    val episode: String,
    val guid: String? = null,
    val action: String,
    val timestamp: String,
    val started: Long? = null,
    val position: Long? = null,
    val total: Long? = null,
)
