// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.network

import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.sonora.core.model.ParsedFeed
import org.sonora.core.parser.RssFeedParser
import org.sonora.core.sync.FeedFetcher

/** An HTTP-level feed failure. [transient] means a retry later may succeed (5xx, 408, 429). */
class FeedFetchException(message: String, val transient: Boolean) : IOException(message)

/** Downloads and parses a feed in one streaming pass; the body is never buffered as a string. */
class OkHttpFeedFetcher(
    client: OkHttpClient,
    private val parser: RssFeedParser,
    private val ioDispatcher: CoroutineDispatcher,
    private val userAgent: String,
    private val allowCleartext: Boolean = false,
) : FeedFetcher {
    private val client = client.withTransportPolicy(allowCleartext)

    override suspend fun fetch(feedUrl: String): ParsedFeed {
        if (!isTransportAllowed(feedUrl, allowCleartext)) {
            throw FeedFetchException("Refusing non-HTTPS feed URL", transient = false)
        }
        return withContext(ioDispatcher) {
            val request = Request.Builder().url(feedUrl).header("User-Agent", userAgent).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val code = response.code
                    throw FeedFetchException("HTTP $code", transient = code >= 500 || code == 408 || code == 429)
                }
                response.body.byteStream().use(parser::parse)
            }
        }
    }
}
