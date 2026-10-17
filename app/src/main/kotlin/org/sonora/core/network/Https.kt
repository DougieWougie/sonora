// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** True when [url] is HTTPS, or when the user has explicitly opted in to cleartext (local development). */
internal fun isTransportAllowed(url: String, allowCleartext: Boolean): Boolean {
    val parsed = url.toHttpUrlOrNull() ?: return false
    return parsed.isHttps || allowCleartext
}

/** Returns [client] unchanged if cleartext is allowed; otherwise one that never follows HTTPS->HTTP redirects. */
internal fun okhttp3.OkHttpClient.withTransportPolicy(allowCleartext: Boolean): okhttp3.OkHttpClient =
    if (allowCleartext) this else newBuilder().followSslRedirects(false).build()
