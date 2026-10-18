// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.download

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import org.sonora.core.database.EpisodeDao
import org.sonora.core.database.EpisodeEntity
import org.sonora.core.database.EpisodeRepository
import org.sonora.core.model.DownloadState
import org.sonora.core.network.isTransportAllowed
import org.sonora.core.network.upgradedToHttps
import org.sonora.core.network.withTransportPolicy

data class DownloadPolicy(val deleteWhenPlayed: Boolean = true)

data class DownloadProgress(val bytesDownloaded: Long, val totalBytes: Long?)

/** [retryable] is true for connectivity problems and server-side errors, false for permanent ones. */
class DownloadException(message: String, val retryable: Boolean, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Downloads episode enclosures into [downloadsDir] and keeps Room (and, through
 * [EpisodeRepository], the sync queue) in step. Partial downloads live in `*.part` files and are
 * resumed with an HTTP `Range` request on the next attempt.
 */
class EpisodeDownloadManager(
    private val episodeDao: EpisodeDao,
    private val episodeRepository: EpisodeRepository,
    client: OkHttpClient,
    private val downloadsDir: File,
    private val policy: suspend () -> DownloadPolicy,
    private val ioDispatcher: CoroutineDispatcher,
    private val allowCleartext: Boolean = false,
    private val progressStepBytes: Long = 256 * 1024,
) {
    private val client = client.withTransportPolicy(allowCleartext)

    /** Marks the episode [DownloadState.QUEUED]. Returns false if there is nothing left to download. */
    suspend fun enqueue(episodeId: Long): Boolean {
        val episode = requireEpisode(episodeId)
        return when (episode.downloadState) {
            DownloadState.DOWNLOADED, DownloadState.DOWNLOADING -> false
            DownloadState.QUEUED -> true
            DownloadState.NONE, DownloadState.FAILED -> {
                episodeRepository.updateDownload(episodeId, DownloadState.QUEUED, episode.downloadedBytes)
                true
            }
        }
    }

    /**
     * Cold flow that performs the download when collected and emits byte progress. Completes after
     * the episode is stored as [DownloadState.DOWNLOADED]; throws [DownloadException] on failure.
     */
    fun download(episodeId: Long): Flow<DownloadProgress> = flow {
        val episode = requireEpisode(episodeId)
        val existing = episode.downloadPath?.let(::File)
        if (episode.downloadState == DownloadState.DOWNLOADED && existing?.isFile == true) {
            emit(DownloadProgress(existing.length(), existing.length()))
            return@flow
        }
        try {
            if (!isTransportAllowed(downloadUrl(episode), allowCleartext)) {
                throw DownloadException("Refusing non-HTTPS download URL", retryable = false)
            }
            downloadsDir.mkdirs()
            transfer(episode) { emit(it) }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                episodeRepository.updateDownload(episodeId, DownloadState.QUEUED, partFile(episode).sizeOrZero())
            }
            throw e
        } catch (e: DownloadException) {
            fail(episode, e.retryable)
            throw e
        } catch (e: IOException) {
            fail(episode, retryable = true)
            throw DownloadException("Download interrupted: ${e.message}", retryable = true, cause = e)
        }
    }.flowOn(ioDispatcher)

    suspend fun downloadToCompletion(episodeId: Long) = download(episodeId).collect()

    /** Marks the episode played, then applies the auto-delete policy. */
    suspend fun markPlayed(episodeId: Long) {
        episodeRepository.markPlayed(episodeId)
        onEpisodeCompleted(episodeId)
    }

    /** Marks every older unplayed episode of the podcast played, applying the auto-delete policy to each. */
    suspend fun markOlderPlayed(episodeId: Long): Int {
        val marked = episodeRepository.markOlderPlayed(episodeId)
        marked.forEach { onEpisodeCompleted(it) }
        return marked.size
    }

    /** Hook for playback completion: removes the local copy (and tells the server) if the policy says so. */
    suspend fun onEpisodeCompleted(episodeId: Long) {
        if (!policy().deleteWhenPlayed) return
        val episode = episodeDao.findById(episodeId) ?: return
        if (episode.downloadState == DownloadState.DOWNLOADED) deleteDownload(episodeId)
    }

    suspend fun deleteDownload(episodeId: Long) {
        val episode = requireEpisode(episodeId)
        withContext(ioDispatcher) {
            episode.downloadPath?.let { File(it).delete() }
            finalFile(episode).delete()
            partFile(episode).delete()
        }
        if (episode.downloadState == DownloadState.DOWNLOADED) {
            episodeRepository.deleteDownload(episodeId)
        } else {
            episodeRepository.updateDownload(episodeId, DownloadState.NONE, 0)
        }
    }

    /**
     * Deletes the oldest *played* downloads until the total size is within [maxBytes]. Unplayed
     * episodes are never evicted. Returns the number of episodes removed.
     */
    suspend fun trimToLimit(maxBytes: Long): Int {
        val downloaded = episodeDao.getByDownloadState(DownloadState.DOWNLOADED)
        var total = downloaded.sumOf { sizeOnDisk(it) }
        var removed = 0
        val evictable = downloaded.filter { it.played }.sortedBy { it.publishedAtMillis ?: Long.MIN_VALUE }
        for (episode in evictable) {
            if (total <= maxBytes) break
            total -= sizeOnDisk(episode)
            deleteDownload(episode.id)
            removed++
        }
        return removed
    }

    /** Feeds often list http:// enclosures; fetch the https:// form unless the user opted in to cleartext. */
    private fun downloadUrl(episode: EpisodeEntity): String =
        if (allowCleartext) episode.enclosureUrl else upgradedToHttps(episode.enclosureUrl) ?: episode.enclosureUrl

    private suspend fun transfer(episode: EpisodeEntity, emit: suspend (DownloadProgress) -> Unit) {
        val part = partFile(episode)
        var offset = if (part.isFile) part.length() else 0L

        while (true) {
            val request = Request.Builder().url(downloadUrl(episode))
                .apply { if (offset > 0) header("Range", "bytes=$offset-") }
                .build()
            val outcome = client.newCall(request).execute().use { response ->
                if (response.code == 416 && offset > 0) {
                    part.delete() // our partial file no longer matches the resource
                    return@use Outcome.RestartFromScratch
                }
                if (!response.isSuccessful) {
                    val code = response.code
                    throw DownloadException("HTTP $code", retryable = code >= 500 || code == 408 || code == 429)
                }
                val resuming = response.code == 206 && offset > 0
                if (!resuming) offset = 0
                val body = response.body
                val total = if (resuming) {
                    response.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                } else {
                    body.contentLength().takeIf { it >= 0 }
                } ?: episode.sizeBytes

                episodeRepository.updateDownload(episode.id, DownloadState.DOWNLOADING, offset)
                var written = offset
                var lastReported = offset
                part.sink(append = resuming).buffer().use { sink ->
                    val source = body.source()
                    while (true) {
                        val read = source.read(sink.buffer, CHUNK_BYTES)
                        if (read == -1L) break
                        sink.emitCompleteSegments()
                        written += read
                        if (written - lastReported >= progressStepBytes) {
                            lastReported = written
                            episodeRepository.updateDownload(episode.id, DownloadState.DOWNLOADING, written)
                            emit(DownloadProgress(written, total))
                        }
                    }
                }
                if (total != null && written != total) {
                    throw IOException("Expected $total bytes but received $written")
                }
                Outcome.Done(written, total, reported = lastReported == written)
            }

            if (outcome is Outcome.Done) {
                val target = finalFile(episode)
                target.delete()
                if (!part.renameTo(target)) throw IOException("Could not move ${part.name} into place")
                episodeRepository.markDownloaded(episode.id, target.absolutePath, outcome.written)
                if (!outcome.reported) emit(DownloadProgress(outcome.written, outcome.total))
                return
            }
            offset = 0
        }
    }

    private suspend fun fail(episode: EpisodeEntity, retryable: Boolean) {
        // A permanent failure leaves nothing worth resuming; a transient one keeps the partial file.
        if (!retryable) partFile(episode).delete()
        episodeRepository.updateDownload(episode.id, DownloadState.FAILED, partFile(episode).sizeOrZero())
    }

    private suspend fun requireEpisode(id: Long): EpisodeEntity =
        episodeDao.findById(id) ?: throw NoSuchElementException("No episode with id $id")

    private fun sizeOnDisk(episode: EpisodeEntity): Long = episode.downloadPath?.let { File(it).sizeOrZero() } ?: 0

    private fun File.sizeOrZero(): Long = if (isFile) length() else 0

    private fun finalFile(episode: EpisodeEntity) = File(downloadsDir, "episode-${episode.id}.${extensionOf(episode)}")
    private fun partFile(episode: EpisodeEntity) = File(downloadsDir, "${finalFile(episode).name}.part")

    private fun extensionOf(episode: EpisodeEntity): String {
        val name = episode.enclosureUrl.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val ext = name.substringAfterLast('.', "").lowercase()
        return if (Regex("[a-z0-9]{2,4}").matches(ext)) ext else "mp3"
    }

    private sealed interface Outcome {
        data object RestartFromScratch : Outcome
        data class Done(val written: Long, val total: Long?, val reported: Boolean) : Outcome
    }

    private companion object {
        const val CHUNK_BYTES = 8L * 1024
    }
}
