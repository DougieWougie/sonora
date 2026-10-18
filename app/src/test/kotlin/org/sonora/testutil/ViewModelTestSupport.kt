// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.testutil

import app.cash.turbine.ReceiveTurbine
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.sonora.core.download.DownloadWaits
import org.sonora.core.download.EpisodeActions
import org.sonora.core.download.WaitReason
import org.sonora.core.media.PlaybackFailure
import org.sonora.core.media.PlaybackPort
import org.sonora.core.media.PlaybackProgress
import org.sonora.core.media.PlaybackState
import org.sonora.core.model.DirectoryPodcast
import org.sonora.core.network.LoginFlowApi
import org.sonora.core.network.LoginFlowException
import org.sonora.core.network.LoginFlowSession
import org.sonora.core.network.NextcloudCredentials
import org.sonora.core.network.discovery.PodcastIndexCredentials
import org.sonora.core.work.SyncController
import org.sonora.feature.discover.DirectorySearch
import org.sonora.feature.discover.DirectorySource
import org.sonora.feature.discover.OpmlImportResult
import org.sonora.feature.discover.OpmlTransfer
import org.sonora.feature.settings.NextcloudAccount
import org.sonora.feature.settings.SettingsStore

/** Installs a [StandardTestDispatcher] as Main, so `viewModelScope` work only runs when the test lets it. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherExtension(val dispatcher: TestDispatcher = StandardTestDispatcher()) :
    BeforeEachCallback, AfterEachCallback {
    override fun beforeEach(context: ExtensionContext) = Dispatchers.setMain(dispatcher)
    override fun afterEach(context: ExtensionContext) = Dispatchers.resetMain()
}

/** Skips intermediate states until one matches; the view models emit loading/partial states on the way. */
suspend fun <T> ReceiveTurbine<T>.awaitUntil(predicate: (T) -> Boolean): T {
    while (true) {
        val item = awaitItem()
        if (predicate(item)) return item
    }
}

class FakeSyncController : SyncController {
    val running = MutableStateFlow(false)
    var requests = 0
    override val syncing: Flow<Boolean> = running
    override fun syncNow() { requests++ }
    val intervals = mutableListOf<Long>()
    override fun setPeriodicInterval(hours: Long) { intervals += hours }
}

class FakeEpisodeActions : EpisodeActions {
    val calls = mutableListOf<String>()
    override suspend fun download(episodeId: Long) { calls += "download:$episodeId" }
    override suspend fun deleteDownload(episodeId: Long) { calls += "delete:$episodeId" }
    override suspend fun cancelDownload(episodeId: Long) { calls += "cancel:$episodeId" }
    var playedDeleted = 0
    var failure: Exception? = null
    override suspend fun deleteAllPlayedDownloads(): Int {
        failure?.let { throw it }
        calls += "deleteAllPlayed"
        return playedDeleted
    }
    override suspend fun markPlayed(episodeId: Long) { calls += "played:$episodeId" }
    override suspend fun markUnplayed(episodeId: Long) { calls += "unplayed:$episodeId" }

    /** How many older unplayed episodes each episode has, as the real repository would count them. */
    val olderCounts = mutableMapOf<Long, Int>()
    override suspend fun countOlderUnplayed(episodeId: Long) = olderCounts[episodeId] ?: 0
    override suspend fun markOlderPlayed(episodeId: Long): Int { calls += "older:$episodeId"; return olderCounts[episodeId] ?: 0 }
}

class FakeSettingsStore : SettingsStore {
    override val lastSyncMillis = MutableStateFlow(0L)
    override fun recordSync(epochMillis: Long) { lastSyncMillis.value = epochMillis }
    var account: NextcloudAccount? = null
    var podcastIndex: PodcastIndexCredentials? = null
    override fun nextcloudAccount() = account
    override fun saveNextcloudAccount(account: NextcloudAccount) { this.account = account }
    override fun clearNextcloudAccount() { account = null }
    override suspend fun podcastIndexCredentials() = podcastIndex
    override fun savePodcastIndexCredentials(credentials: PodcastIndexCredentials?) { podcastIndex = credentials?.takeIf { it.isComplete } }
    override var autoDownloadUnmeteredOnly = true
    override var deleteDownloadsWhenPlayed = true
    override var allowCleartext = false
    override var autoDownloadChargingOnly = false
    override var autoDownloadPerPodcast = 3
    override var keepPerPodcast = 0
    override var maxStorageBytes = 0L
    override var syncIntervalHours = 6L
}

class FakeLoginFlow : LoginFlowApi {
    var startResult: Result<LoginFlowSession> = Result.success(LoginFlowSession("https://cloud/login/flow", "https://cloud/poll", "tok"))
    val startedFor = mutableListOf<String>()
    /** Responses handed out one per poll; the last one repeats. */
    var pollResults: MutableList<Result<NextcloudCredentials?>> = mutableListOf(Result.success(null))
    var polls = 0

    override suspend fun start(serverUrl: String): LoginFlowSession {
        startedFor += serverUrl
        return startResult.getOrThrow()
    }

    override suspend fun poll(session: LoginFlowSession): NextcloudCredentials? {
        val result = pollResults[minOf(polls, pollResults.lastIndex)]
        polls++
        return result.getOrThrow()
    }

    companion object {
        fun failure(message: String = "network") = Result.failure<NextcloudCredentials?>(LoginFlowException(message))
    }
}

class FakeDirectorySearch : DirectorySearch {
    val calls = mutableListOf<Pair<String, DirectorySource>>()
    var results: (String, DirectorySource) -> List<DirectoryPodcast> = { _, _ -> emptyList() }
    var failure: Throwable? = null
    override fun search(query: String, source: DirectorySource): Flow<List<DirectoryPodcast>> = flow {
        calls += query to source
        failure?.let { throw it }
        emit(results(query, source))
    }
}

class FakeOpmlTransfer : OpmlTransfer {
    var importResult = OpmlImportResult(0, 0)
    var failure: Exception? = null
    var exported = 0
    override suspend fun import(input: InputStream): OpmlImportResult {
        failure?.let { throw it }
        return importResult
    }

    override suspend fun export(output: OutputStream): Int {
        failure?.let { throw it }
        return exported
    }
}

class FakePlaybackPort : PlaybackPort {
    val stateFlow = MutableStateFlow(PlaybackState())
    val progressFlow = MutableStateFlow(PlaybackProgress.None)
    val errorFlow = MutableSharedFlow<PlaybackFailure>(extraBufferCapacity = 8)
    val calls = mutableListOf<String>()
    var failure: Exception? = null

    override val state: StateFlow<PlaybackState> = stateFlow
    override val errors: Flow<PlaybackFailure> = errorFlow
    override fun progress(): Flow<PlaybackProgress> = progressFlow

    override suspend fun play(episodeId: Long) {
        failure?.let { throw it }
        calls += "play:$episodeId"
        stateFlow.value = stateFlow.value.copy(episodeId = episodeId, isPlaying = true)
    }

    override suspend fun pause() {
        calls += "pause"
        stateFlow.value = stateFlow.value.copy(isPlaying = false)
    }

    override suspend fun seekTo(positionMs: Long) { calls += "seek:$positionMs" }
    override fun setSpeed(speed: Float) { calls += "speed:$speed"; stateFlow.value = stateFlow.value.copy(speed = speed) }
    override fun setSkipSilence(enabled: Boolean) { calls += "silence:$enabled"; stateFlow.value = stateFlow.value.copy(skipSilence = enabled) }
    override fun startSleepTimer(durationMs: Long) { calls += "sleep:$durationMs"; stateFlow.value = stateFlow.value.copy(sleepTimerRemainingMs = durationMs) }
    override fun cancelSleepTimer() { calls += "sleep:cancel"; stateFlow.value = stateFlow.value.copy(sleepTimerRemainingMs = null) }
}

class FakeDownloadWaits : DownloadWaits {
    val reasons = MutableStateFlow<Map<Long, WaitReason>>(emptyMap())
    override fun observe(): Flow<Map<Long, WaitReason>> = reasons
}
