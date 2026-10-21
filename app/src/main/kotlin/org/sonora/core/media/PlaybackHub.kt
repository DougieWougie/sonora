// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.media

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withTimeout

/**
 * Process-wide handle on the [PlaybackStateManager] that lives inside the media service, so view
 * models can observe and drive playback without owning a player. The service attaches while it runs;
 * commands issued before that wait up to [timeoutMs] for the service to come up.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackHub(private val timeoutMs: Long = 5_000) : PlaybackPort {
    private val manager = MutableStateFlow<PlaybackStateManager?>(null)

    fun attach(manager: PlaybackStateManager) {
        this.manager.value = manager
    }

    fun detach(manager: PlaybackStateManager) {
        this.manager.compareAndSet(manager, null)
    }

    override val state: Flow<PlaybackState> = manager.flatMapLatest { it?.state ?: flowOf(PlaybackState()) }

    override fun progress(): Flow<PlaybackProgress> =
        manager.flatMapLatest { it?.progress() ?: flowOf(PlaybackProgress.None) }

    private val hubErrors = MutableSharedFlow<PlaybackFailure>(extraBufferCapacity = 8)

    override val errors: Flow<PlaybackFailure> = merge(manager.flatMapLatest { it?.errors ?: emptyFlow() }, hubErrors)

    private suspend fun awaitManager(): PlaybackStateManager =
        manager.value ?: try {
            withTimeout(timeoutMs) { manager.filterNotNull().first() }
        } catch (e: TimeoutCancellationException) {
            hubErrors.tryEmit(PlaybackFailure(PlaybackFailureReason.OTHER))
            throw e
        }

    override suspend fun play(episodeId: Long) = awaitManager().play(episodeId)
    override suspend fun pause() = awaitManager().pause()
    override suspend fun seekTo(positionMs: Long) = awaitManager().seekTo(positionMs)
    override suspend fun next() = awaitManager().next()
    override suspend fun previous() = awaitManager().previous()
    override fun setSpeed(speed: Float) { manager.value?.setSpeed(speed) }
    override fun setSkipSilence(enabled: Boolean) { manager.value?.setSkipSilence(enabled) }
    override fun startSleepTimer(durationMs: Long) { manager.value?.startSleepTimer(durationMs) }
    override fun cancelSleepTimer() { manager.value?.cancelSleepTimer() }
}
