// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** True when [url] is HTTPS, or when the user has explicitly opted in to cleartext (local development). */
internal fun isTransportAllowed(url: String, allowCleartext: Boolean): Boolean {
    val parsed = url.toHttpUrlOrNull() ?: return false
    return parsed.isHttps || allowCleartext
}

/**
 * Podcast hosts often redirect an https:// enclosure to an http:// CDN address. Rewrite such redirects to
 * https:// so they can be followed without ever sending the request in cleartext.
 */
internal class HttpsRedirectInterceptor : okhttp3.Interceptor {
    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        val response = chain.proceed(chain.request())
        val upgraded = response.header("Location")?.let(::upgradedToHttps) ?: return response
        return response.newBuilder().header("Location", upgraded).build()
    }
}

/** Returns [client] unchanged if cleartext is allowed; otherwise one that never follows HTTPS->HTTP redirects. */
internal fun okhttp3.OkHttpClient.withTransportPolicy(allowCleartext: Boolean): okhttp3.OkHttpClient =
    if (allowCleartext) this else newBuilder().followSslRedirects(false).addNetworkInterceptor(HttpsRedirectInterceptor()).build()

/** The `https://` form of an `http://` address, or null when [url] needs no upgrade. */
fun upgradedToHttps(url: String): String? =
    if (url.startsWith("http://", ignoreCase = true)) "https://" + url.substring("http://".length) else null
