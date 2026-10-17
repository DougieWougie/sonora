// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.ui

import coil3.intercept.Interceptor
import coil3.request.ImageResult

/** The `https://` form of an `http://` address, or null when [url] needs no upgrade. */
fun upgradedToHttps(url: String): String? =
    if (url.startsWith("http://", ignoreCase = true)) "https://" + url.substring("http://".length) else null

/**
 * Many feeds still list `http://` artwork. With cleartext off, ask for the `https://` address instead
 * of failing outright (or, worse, loading it unencrypted).
 */
class HttpsUpgradeInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val data = chain.request.data
        val upgraded = (data as? String)?.let(::upgradedToHttps)
            ?: return chain.proceed()
        return chain.withRequest(chain.request.newBuilder().data(upgraded).build()).proceed()
    }
}
