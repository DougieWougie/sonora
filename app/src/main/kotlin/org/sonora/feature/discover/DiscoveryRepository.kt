// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.discover

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.sonora.core.model.DirectoryPodcast
import org.sonora.core.network.discovery.DirectoryException
import org.sonora.core.network.discovery.DiscoveryCredentialsSource
import org.sonora.core.network.discovery.PodcastDirectoryProvider
import org.sonora.core.network.discovery.PodcastIndexCredentials

enum class DirectorySource { APPLE, PODCAST_INDEX }

/**
 * Searches Apple Podcasts by default; when the user has supplied PodcastIndex credentials it also
 * queries PodcastIndex and merges (PodcastIndex first, de-duplicated by feed URL).
 *
 * Emits the merged list after each directory answers. A failing directory is skipped as long as
 * another one succeeds; if all fail, the first failure is thrown.
 */
class DiscoveryRepository(
    private val apple: PodcastDirectoryProvider,
    private val credentials: DiscoveryCredentialsSource,
    private val podcastIndexFor: (PodcastIndexCredentials) -> PodcastDirectoryProvider,
) : DirectorySearch {
    fun search(query: String): Flow<List<DirectoryPodcast>> = flow {
        val index = credentials.podcastIndexCredentials()?.takeIf { it.isComplete }?.let(podcastIndexFor)
        val providers = listOfNotNull(index, apple)

        var merged = emptyList<DirectoryPodcast>()
        var firstFailure: Exception? = null
        var succeeded = false
        for (provider in providers) {
            try {
                merged = (merged + provider.search(query)).distinctBy { it.feedUrl.normalizedFeed() }
                succeeded = true
                emit(merged)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                firstFailure = firstFailure ?: e
            }
        }
        if (!succeeded) throw firstFailure ?: IllegalStateException("No directory configured")
    }

    /** Searches exactly one directory; its failure is the caller's error, never papered over by the other. */
    override fun search(query: String, source: DirectorySource): Flow<List<DirectoryPodcast>> = flow {
        val provider = when (source) {
            DirectorySource.APPLE -> apple
            DirectorySource.PODCAST_INDEX -> credentials.podcastIndexCredentials()?.takeIf { it.isComplete }
                ?.let(podcastIndexFor)
                ?: throw DirectoryException("PodcastIndex API key and secret are not configured")
        }
        emit(provider.search(query).distinctBy { it.feedUrl.normalizedFeed() })
    }

    private fun String.normalizedFeed() = trim().trimEnd('/').lowercase()
}
