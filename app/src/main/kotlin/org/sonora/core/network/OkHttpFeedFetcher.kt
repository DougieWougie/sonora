// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.network

import java.io.IOException
import java.time.Duration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.sonora.core.model.ParsedFeed
import org.sonora.core.parser.RssFeedParser
import org.sonora.core.sync.FeedFetchResult
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
    callTimeout: Duration = FEED_CALL_TIMEOUT,
) : FeedFetcher {
    // The whole call (connect, headers and body) is bounded, so a feed that trickles bytes cannot hold a worker forever.
    private val client = client.newBuilder().callTimeout(callTimeout).build()

    override suspend fun fetch(feedUrl: String): ParsedFeed =
        (fetchConditional(feedUrl, etag = null, lastModified = null) as FeedFetchResult.Updated).feed

    override suspend fun fetchConditional(
        feedUrl: String,
        etag: String?,
        lastModified: String?,
    ): FeedFetchResult {
        if (!isTransportAllowed(feedUrl, allowCleartext)) {
            throw FeedFetchException("Refusing non-HTTPS feed URL", transient = false)
        }
        return withContext(ioDispatcher) {
            val request = Request.Builder()
                .url(feedUrl)
                .header("User-Agent", userAgent)
                .apply {
                    if (!etag.isNullOrBlank()) header("If-None-Match", etag)
                    if (!lastModified.isNullOrBlank()) header("If-Modified-Since", lastModified)
                }
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code == 304) return@use FeedFetchResult.NotModified
                if (!response.isSuccessful) {
                    val code = response.code
                    throw FeedFetchException("HTTP $code", transient = code >= 500 || code == 408 || code == 429)
                }
                val parsed = response.body.byteStream().use(parser::parse)
                FeedFetchResult.Updated(
                    feed = parsed,
                    etag = response.header("ETag"),
                    lastModified = response.header("Last-Modified"),
                )
            }
        }
    }
}

/** Generous for a slow link and a large feed, but finite. */
private val FEED_CALL_TIMEOUT: Duration = Duration.ofMinutes(2)
