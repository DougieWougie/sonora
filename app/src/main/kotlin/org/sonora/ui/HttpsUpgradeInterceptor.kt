// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.ui

import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import org.sonora.core.network.isTransportAllowed
import org.sonora.core.network.upgradedToHttps

/**
 * Guards artwork loads from untrusted RSS feeds: rejects non-HTTP(S) schemes (such as `file://`,
 * `content://`, or `data:`) and, when cleartext is disallowed, upgrades `http://` artwork URLs to
 * `https://` instead of failing outright.
 */
class HttpsUpgradeInterceptor(val allowCleartext: Boolean = false) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val url = chain.request.data as? String ?: return chain.proceed()
        val candidate = if (allowCleartext) url else upgradedToHttps(url) ?: url
        if (!isTransportAllowed(candidate, allowCleartext)) {
            return ErrorResult(null, chain.request, IllegalArgumentException("Refusing artwork URL scheme"))
        }
        if (candidate == url) return chain.proceed()
        return chain.withRequest(chain.request.newBuilder().data(candidate).build()).proceed()
    }
}
