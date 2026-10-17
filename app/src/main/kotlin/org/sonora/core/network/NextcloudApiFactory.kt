// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.network

import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

internal val syncJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

object NextcloudApiFactory {
    fun create(
        serverUrl: String,
        loginName: String,
        appPassword: String,
        client: OkHttpClient = OkHttpClient(),
        allowCleartext: Boolean = false,
    ): GpodderSyncApi {
        require(isTransportAllowed(serverUrl, allowCleartext)) { "Refusing non-HTTPS server URL" }
        val authed = client.withTransportPolicy(allowCleartext).newBuilder()
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("Authorization", Credentials.basic(loginName, appPassword))
                        .header("Accept", "application/json")
                        .build(),
                )
            }
            .build()
        return Retrofit.Builder()
            .baseUrl(if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/")
            .client(authed)
            .addConverterFactory(syncJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(GpodderSyncApi::class.java)
    }
}
