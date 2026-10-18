// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.sync

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.sonora.core.database.EpisodeDao
import org.sonora.core.database.PodcastDao
import org.sonora.core.database.PodcastEntity
import org.sonora.core.database.PodcastRepository
import org.sonora.core.model.DownloadState
import org.sonora.core.model.ParsedFeed
import org.sonora.core.network.FeedFetchException
import retrofit2.HttpException

/** Fetches and parses one feed. Implementations throw [java.io.IOException] for network problems. */
fun interface FeedFetcher {
    suspend fun fetch(feedUrl: String): ParsedFeed
}

/** The server-sync step (Nextcloud). Returns null when no account is configured. */
fun interface RemoteSync {
    suspend fun sync(): SyncReport?
}

/** Queues episodes for background download, honouring the unmetered-only preference. */
fun interface DownloadScheduler {
    suspend fun schedule(episodeIds: List<Long>, unmeteredOnly: Boolean, chargingOnly: Boolean)
}

/** Stops the background job for an episode's download, if there is one. */
fun interface DownloadCanceller {
    fun cancel(episodeId: Long)
}

enum class FailureKind { TRANSIENT, PERMANENT, AUTH }

sealed interface RemoteStatus {
    data object NotConfigured : RemoteStatus
    data class Synced(val report: SyncReport) : RemoteStatus
    data class Failed(val kind: FailureKind, val cause: Throwable? = null) : RemoteStatus
}

enum class WorkOutcome { SUCCESS, RETRY, FAILURE }

data class SyncOutcome(
    val remote: RemoteStatus,
    val refreshedFeeds: Int,
    val failedFeeds: Map<String, FailureKind>,
    val queuedDownloads: Int,
) {
    /**
     * A flaky single feed is not worth a retry; losing the sync server or every feed to connectivity
     * is. Rejected credentials will not fix themselves, so they fail instead of retrying.
     */
    fun toWorkOutcome(attempt: Int, maxAttempts: Int = DEFAULT_MAX_ATTEMPTS): WorkOutcome {
        val remoteKind = (remote as? RemoteStatus.Failed)?.kind
        val allFeedsOffline = failedFeeds.isNotEmpty() && refreshedFeeds == 0 &&
            failedFeeds.values.all { it == FailureKind.TRANSIENT }
        if (remoteKind == FailureKind.TRANSIENT || allFeedsOffline) {
            return if (attempt >= maxAttempts) WorkOutcome.FAILURE else WorkOutcome.RETRY
        }
        return if (remoteKind != null) WorkOutcome.FAILURE else WorkOutcome.SUCCESS
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 5
    }
}

/**
 * The background sync run: server sync, then feed refreshes (bounded parallelism), then
 * auto-download scheduling, then retention. Each step is isolated, so a failure in one never prevents the rest.
 *
 * [RemoteSync] is the existing pull -> resolve -> push reconciliation: pushing before pulling would
 * let local state always win and defeat latest-timestamp-wins conflict resolution.
 */
class PodcastSyncPipeline(
    private val remoteSync: RemoteSync,
    private val podcastDao: PodcastDao,
    private val episodeDao: EpisodeDao,
    private val podcastRepository: PodcastRepository,
    private val feedFetcher: FeedFetcher,
    private val downloads: DownloadScheduler,
    private val unmeteredOnlyForAutoDownload: suspend () -> Boolean,
    private val chargingOnlyForAutoDownload: suspend () -> Boolean = { false },
    private val enforceRetention: suspend () -> Unit = {},
    private val maxConcurrentRefreshes: Int = 4,
    private val maxAutoDownloadsPerPodcast: suspend () -> Int = { 3 },
) {
    suspend fun run(): SyncOutcome {
        val remote = syncRemote()

        // After the remote step so subscriptions it just added are refreshed in the same run.
        val results = refreshAll(podcastDao.getAll())
        val failed = results.mapNotNull { r -> r.failure?.let { r.feedUrl to it } }.toMap()
        val toDownload = results.flatMap { it.newEpisodeIds }

        if (toDownload.isNotEmpty()) downloads.schedule(toDownload, unmeteredOnlyForAutoDownload(), chargingOnly = chargingOnlyForAutoDownload())
        retain()
        return SyncOutcome(
            remote = remote,
            refreshedFeeds = results.count { it.failure == null },
            failedFeeds = failed,
            queuedDownloads = toDownload.size,
        )
    }

    /** Housekeeping: a failure here must not turn an otherwise good sync into a failed one. */
    private suspend fun retain() {
        try {
            enforceRetention()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private suspend fun syncRemote(): RemoteStatus = try {
        remoteSync.sync()?.let { RemoteStatus.Synced(it) } ?: RemoteStatus.NotConfigured
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        RemoteStatus.Failed(classify(e), e)
    }

    private class FeedResult(val feedUrl: String, val failure: FailureKind?, val newEpisodeIds: List<Long>)

    private suspend fun refreshAll(podcasts: List<PodcastEntity>): List<FeedResult> = coroutineScope {
        val permits = Semaphore(maxConcurrentRefreshes)
        podcasts.map { podcast -> async { permits.withPermit { refresh(podcast) } } }.awaitAll()
    }

    private suspend fun refresh(podcast: PodcastEntity): FeedResult = try {
        val knownGuids = episodeDao.getForPodcast(podcast.id).mapTo(HashSet()) { it.guid }
        val feed = feedFetcher.fetch(podcast.feedUrl)
        podcastRepository.refresh(podcast.feedUrl, feed)

        // An empty podcast is being fetched for the first time: don't pull its whole back catalogue.
        val fresh = if (podcast.autoDownload && knownGuids.isNotEmpty()) {
            episodeDao.getForPodcast(podcast.id)
                .filter { it.guid !in knownGuids && it.downloadState == DownloadState.NONE && !it.played }
                .sortedByDescending { it.publishedAtMillis ?: Long.MIN_VALUE }
                .take(maxAutoDownloadsPerPodcast())
                .map { it.id }
        } else {
            emptyList()
        }
        FeedResult(podcast.feedUrl, null, fresh)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        FeedResult(podcast.feedUrl, classify(e), emptyList())
    }

    private fun classify(e: Exception): FailureKind = when {
        e is SyncAuthException -> FailureKind.AUTH
        e is FeedFetchException -> if (e.transient) FailureKind.TRANSIENT else FailureKind.PERMANENT
        e is IOException -> FailureKind.TRANSIENT
        e is SyncException && (e.cause as? HttpException)?.code()?.let { it >= 500 || it == 408 || it == 429 } == true ->
            FailureKind.TRANSIENT
        else -> FailureKind.PERMANENT
    }
}
