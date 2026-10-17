// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.media

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.sonora.core.database.EpisodeDao
import org.sonora.core.database.EpisodeEntity
import org.sonora.core.database.EpisodeRepository
import org.sonora.core.database.PodcastDao
import org.sonora.core.model.DownloadState
import org.sonora.core.network.isTransportAllowed
import org.sonora.core.network.upgradedToHttps

data class PlaybackState(
    val episodeId: Long? = null,
    val isPlaying: Boolean = false,
    val speed: Float = 1f,
    val skipSilence: Boolean = false,
    val sleepTimerRemainingMs: Long? = null,
)

/**
 * Playback domain rules on top of a [PlayerEngine]: source selection, progress persistence,
 * gpodder `PLAY` action generation, speed, silence skipping and the sleep timer.
 *
 * Not thread-safe: call every method from the one dispatcher that owns the player (Main), and pass
 * a [scope] on that dispatcher.
 */
class PlaybackStateManager(
    private val engine: PlayerEngine,
    private val episodeDao: EpisodeDao,
    private val podcastDao: PodcastDao,
    private val episodeRepository: EpisodeRepository,
    private val fileExists: (String) -> Boolean,
    private val scope: CoroutineScope,
    private val progressIntervalMs: Long = 10_000,
    private val completionWindowSeconds: Long = 30,
    private val allowCleartext: Boolean = false,
    private val queue: PlayQueue? = null,
    private val onEpisodeCompleted: suspend (episodeId: Long) -> Unit = {},
) {
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state

    private var loadedId: Long? = null
    private var loadedDurationMs = 0L
    private var sessionStartSeconds = 0L
    private var progressJob: Job? = null
    private var sleepJob: Job? = null

    suspend fun play(episodeId: Long) {
        if (loadedId == episodeId) {
            sessionStartSeconds = engine.positionMs / 1000
        } else {
            val episode = episodeDao.findById(episodeId)
                ?: throw NoSuchElementException("No episode with id $episodeId")
            if (loadedId != null) checkpoint()
            val saved = episode.playbackPositionSeconds
            val finished = episode.durationSeconds?.let { saved >= it } == true
            val startSeconds = if (finished) 0 else saved
            val podcast = podcastDao.findById(episode.podcastId)
            val metadata = PlaybackMetadata(
                mediaId = episode.id.toString(),
                title = episode.title,
                artist = podcast?.title,
                artworkUrl = (episode.imageUrl ?: podcast?.imageUrl)
                    ?.let { if (allowCleartext) it else upgradedToHttps(it) ?: it },
            )
            engine.prepare(resolveUri(episode), startSeconds * 1000, metadata)
            sessionStartSeconds = startSeconds
            loadedId = episodeId
            loadedDurationMs = (episode.durationSeconds ?: 0) * 1000
        }
        engine.play()
        queue?.remove(episodeId)
        _state.update { it.copy(episodeId = episodeId, isPlaying = true) }
        startProgressTicker(episodeId)
    }

    suspend fun pause() {
        if (loadedId == null) return
        progressJob?.cancel()
        engine.pause()
        _state.update { it.copy(isPlaying = false) }
        checkpoint()
    }

    suspend fun stop() {
        if (loadedId == null) return
        progressJob?.cancel()
        engine.pause()
        checkpoint()
        engine.stop()
        loadedId = null
        loadedDurationMs = 0
        _state.update { it.copy(episodeId = null, isPlaying = false) }
    }

    suspend fun seekTo(positionMs: Long) {
        if (loadedId == null) return
        engine.seekTo(positionMs)
        checkpoint()
        sessionStartSeconds = positionMs / 1000
    }

    /** Called by the engine adapter when the media item has played to its end. */
    suspend fun onPlaybackEnded() {
        if (loadedId == null) return
        progressJob?.cancel()
        _state.update { it.copy(isPlaying = false) }
        checkpoint(forceComplete = true)
        playNextInQueue()
    }

    /** Starts the first queued episode that still exists. */
    private suspend fun playNextInQueue() {
        val queue = queue ?: return
        while (true) {
            val next = queue.pop() ?: return
            if (episodeDao.findById(next) != null) return play(next)
        }
    }

    /** Polls the engine for the scrubber; only distinct values are emitted. Collect while the UI is visible. */
    fun progress(): Flow<PlaybackProgress> = flow {
        while (true) {
            emit(
                if (loadedId == null) {
                    PlaybackProgress.None
                } else {
                    PlaybackProgress(
                        positionMs = engine.positionMs,
                        bufferedMs = engine.bufferedMs,
                        durationMs = engine.durationMs.takeIf { it > 0 } ?: loadedDurationMs,
                    )
                },
            )
            delay(PROGRESS_POLL_MS)
        }
    }.distinctUntilChanged()

    fun setSpeed(speed: Float) {
        if (!speed.isFinite()) return
        val clamped = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        engine.setSpeed(clamped)
        _state.update { it.copy(speed = clamped) }
    }

    fun setSkipSilence(enabled: Boolean) {
        engine.setSkipSilence(enabled)
        _state.update { it.copy(skipSilence = enabled) }
    }

    fun startSleepTimer(durationMs: Long) {
        require(durationMs > 0) { "Sleep timer must be positive" }
        sleepJob?.cancel()
        _state.update { it.copy(sleepTimerRemainingMs = durationMs) }
        sleepJob = scope.launch {
            var remaining = durationMs
            while (remaining > 0) {
                val step = minOf(SLEEP_TICK_MS, remaining)
                delay(step)
                remaining -= step
                _state.update { it.copy(sleepTimerRemainingMs = remaining.takeIf { r -> r > 0 }) }
            }
            pause()
        }
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel()
        _state.update { it.copy(sleepTimerRemainingMs = null) }
    }

    private fun resolveUri(episode: EpisodeEntity): String {
        val path = episode.downloadPath
        return if (episode.downloadState == DownloadState.DOWNLOADED && path != null && fileExists(path)) {
            File(path).toURI().let { "file://${it.rawPath}" }
        } else {
            // Many feeds still list http:// enclosures; ask for https:// rather than refusing outright.
            val url = episode.enclosureUrl.takeIf { allowCleartext } ?: upgradedToHttps(episode.enclosureUrl) ?: episode.enclosureUrl
            check(isTransportAllowed(url, allowCleartext)) {
                "Refusing to stream ${episode.enclosureUrl}: not HTTP(S)"
            }
            url
        }
    }

    private fun startProgressTicker(episodeId: Long) {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (true) {
                delay(progressIntervalMs)
                episodeRepository.savePosition(episodeId, engine.positionMs / 1000)
            }
        }
    }

    /** Persists the position and queues a `PLAY` action; the last [completionWindowSeconds] count as finished. */
    private suspend fun checkpoint(forceComplete: Boolean = false) {
        val id = loadedId ?: return
        val episode = episodeDao.findById(id) ?: return
        var position = engine.positionMs / 1000
        val total = episode.durationSeconds?.takeIf { it > 0 }
        if (total != null && (forceComplete || total - position <= completionWindowSeconds)) position = total
        episodeRepository.recordPlayback(id, minOf(sessionStartSeconds, position), position)
        if (!episode.played && episodeDao.findById(id)?.played == true) onEpisodeCompleted(id)
    }

    companion object {
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 3.0f
        private const val SLEEP_TICK_MS = 1_000L
        private const val PROGRESS_POLL_MS = 500L
    }
}
