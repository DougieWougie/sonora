// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.network.discovery

import org.sonora.core.model.DirectoryPodcast

interface PodcastDirectoryProvider {
    /** Never returns results without a feed URL. A blank [query] returns an empty list. */
    suspend fun search(query: String): List<DirectoryPodcast>
}

class DirectoryException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class PodcastIndexCredentials(val apiKey: String, val apiSecret: String) {
    val isComplete: Boolean get() = apiKey.isNotBlank() && apiSecret.isNotBlank()
}

/** Reads user-supplied directory credentials from preferences. */
interface DiscoveryCredentialsSource {
    suspend fun podcastIndexCredentials(): PodcastIndexCredentials?
}

internal suspend inline fun <T> directoryCall(block: () -> T): T = try {
    block()
} catch (e: retrofit2.HttpException) {
    throw DirectoryException("Directory returned HTTP ${e.code()}", e)
} catch (e: java.io.IOException) {
    throw DirectoryException("Directory request failed: ${e.message}", e)
} catch (e: kotlinx.serialization.SerializationException) {
    throw DirectoryException("Unreadable directory response", e)
}
