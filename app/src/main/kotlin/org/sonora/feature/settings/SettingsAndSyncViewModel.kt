// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.sonora.core.network.LoginFlowApi
import org.sonora.core.network.LoginFlowException
import org.sonora.core.network.LoginFlowSession
import org.sonora.core.network.NextcloudCredentials
import org.sonora.core.network.discovery.PodcastIndexCredentials
import org.sonora.core.network.isTransportAllowed
import org.sonora.core.work.SyncController

sealed interface NextcloudState {
    data object Idle : NextcloudState
    data class WaitingForBrowserAuth(val loginUrl: String) : NextcloudState
    data object Polling : NextcloudState
    data class Connected(val username: String, val lastSyncTime: Long?) : NextcloudState
    data class Error(val message: String) : NextcloudState
}

data class PodcastIndexForm(val apiKey: String = "", val apiSecret: String = "", val saved: Boolean = false)

data class DownloadPrefs(val unmeteredOnly: Boolean, val deleteWhenPlayed: Boolean)

data class SettingsUiState(
    val nextcloud: NextcloudState,
    val isSyncing: Boolean = false,
    val podcastIndex: PodcastIndexForm = PodcastIndexForm(),
    val downloads: DownloadPrefs,
    val allowCleartext: Boolean,
    /** Cleartext is read once at startup, so a change only takes effect after a restart. */
    val restartRequired: Boolean = false,
)

sealed interface SettingsIntent {
    data class StartLogin(val serverUrl: String) : SettingsIntent
    /** The UI opened the login URL; start polling for the approval. */
    data object BrowserLaunched : SettingsIntent
    /** The user came back from the browser; Login Flow v2 has no redirect, so check for approval now. */
    data object ReturnedToApp : SettingsIntent
    data object CancelLogin : SettingsIntent
    data object Disconnect : SettingsIntent
    data object SyncNow : SettingsIntent
    data class EditPodcastIndexKey(val value: String) : SettingsIntent
    data class EditPodcastIndexSecret(val value: String) : SettingsIntent
    data object SavePodcastIndex : SettingsIntent
    data object ClearPodcastIndex : SettingsIntent
    data class SetUnmeteredOnly(val enabled: Boolean) : SettingsIntent
    data class SetDeleteWhenPlayed(val enabled: Boolean) : SettingsIntent
    data class SetAllowCleartext(val enabled: Boolean) : SettingsIntent
}

sealed interface SettingsEvent {
    data class OpenLoginUrl(val url: String) : SettingsEvent
}

@HiltViewModel
class SettingsAndSyncViewModel @Inject constructor(
    private val login: LoginFlowApi,
    private val settings: SettingsStore,
    private val sync: SyncController,
) : ViewModel() {
    private val cleartextAtStart = settings.allowCleartext

    private val _state = MutableStateFlow(
        SettingsUiState(
            nextcloud = settings.nextcloudAccount()?.let { NextcloudState.Connected(it.loginName, settings.lastSyncMillis.value.orNull()) }
                ?: NextcloudState.Idle,
            downloads = DownloadPrefs(settings.autoDownloadUnmeteredOnly, settings.deleteDownloadsWhenPlayed),
            allowCleartext = cleartextAtStart,
        ),
    )
    val uiState: StateFlow<SettingsUiState> = _state.asStateFlow()

    private val _events = Channel<SettingsEvent>(Channel.BUFFERED)
    val events: Flow<SettingsEvent> = _events.receiveAsFlow()

    private var session: LoginFlowSession? = null
    private var loginJob: Job? = null

    init {
        viewModelScope.launch {
            settings.podcastIndexCredentials()?.let { c ->
                _state.update { it.copy(podcastIndex = PodcastIndexForm(c.apiKey, c.apiSecret, saved = true)) }
            }
        }
        viewModelScope.launch {
            settings.lastSyncMillis.collect { millis ->
                _state.update { s ->
                    val nc = s.nextcloud
                    if (nc is NextcloudState.Connected) s.copy(nextcloud = nc.copy(lastSyncTime = millis.orNull())) else s
                }
            }
        }
        viewModelScope.launch { sync.syncing.collect { running -> _state.update { it.copy(isSyncing = running) } } }
    }

    fun onIntent(intent: SettingsIntent) {
        when (intent) {
            is SettingsIntent.StartLogin -> startLogin(intent.serverUrl)
            SettingsIntent.BrowserLaunched -> startPolling()
            SettingsIntent.ReturnedToApp -> if (_state.value.nextcloud == NextcloudState.Polling) startPolling()
            SettingsIntent.CancelLogin -> {
                stopLogin()
                setNextcloud(NextcloudState.Idle)
            }
            SettingsIntent.Disconnect -> {
                stopLogin()
                settings.clearNextcloudAccount()
                setNextcloud(NextcloudState.Idle)
            }
            SettingsIntent.SyncNow -> if (_state.value.nextcloud is NextcloudState.Connected) sync.syncNow()
            is SettingsIntent.EditPodcastIndexKey -> editForm { it.copy(apiKey = intent.value, saved = false) }
            is SettingsIntent.EditPodcastIndexSecret -> editForm { it.copy(apiSecret = intent.value, saved = false) }
            SettingsIntent.SavePodcastIndex -> savePodcastIndex()
            SettingsIntent.ClearPodcastIndex -> {
                settings.savePodcastIndexCredentials(null)
                _state.update { it.copy(podcastIndex = PodcastIndexForm()) }
            }
            is SettingsIntent.SetUnmeteredOnly -> {
                settings.autoDownloadUnmeteredOnly = intent.enabled
                _state.update { it.copy(downloads = it.downloads.copy(unmeteredOnly = intent.enabled)) }
            }
            is SettingsIntent.SetDeleteWhenPlayed -> {
                settings.deleteDownloadsWhenPlayed = intent.enabled
                _state.update { it.copy(downloads = it.downloads.copy(deleteWhenPlayed = intent.enabled)) }
            }
            is SettingsIntent.SetAllowCleartext -> {
                settings.allowCleartext = intent.enabled
                _state.update { it.copy(allowCleartext = intent.enabled, restartRequired = intent.enabled != cleartextAtStart) }
            }
        }
    }

    // --- login flow --------------------------------------------------------------------------

    private fun startLogin(rawUrl: String) {
        stopLogin()
        val raw = rawUrl.trim()
        val url = (if (raw.contains("://")) raw else "https://$raw").trimEnd('/')
        val error = when {
            raw.isEmpty() || url.toHttpUrlOrNull() == null -> "Enter your Nextcloud server address"
            !isTransportAllowed(url, settings.allowCleartext) ->
                "Nextcloud must be reached over HTTPS. Cleartext can be enabled below for local testing."
            else -> null
        }
        if (error != null) return setNextcloud(NextcloudState.Error(error))

        loginJob = viewModelScope.launch {
            try {
                val started = login.start(url)
                // The login URL comes from the server's response: hold it to the same transport policy.
                if (started.loginUrl.toHttpUrlOrNull() == null || !isTransportAllowed(started.loginUrl, settings.allowCleartext)) {
                    return@launch setNextcloud(NextcloudState.Error("The server returned an insecure login address (HTTPS is required)."))
                }
                session = started
                setNextcloud(NextcloudState.WaitingForBrowserAuth(started.loginUrl))
                _events.trySend(SettingsEvent.OpenLoginUrl(started.loginUrl))
            } catch (e: CancellationException) {
                throw e
            } catch (e: LoginFlowException) {
                setNextcloud(NextcloudState.Error(e.message ?: "Couldn't start login"))
            }
        }
    }

    private fun startPolling() {
        val active = session ?: return
        if (_state.value.nextcloud !is NextcloudState.WaitingForBrowserAuth && _state.value.nextcloud != NextcloudState.Polling) return
        setNextcloud(NextcloudState.Polling)
        loginJob?.cancel()
        loginJob = viewModelScope.launch {
            var waited = 0L
            var failures = 0
            while (true) {
                try {
                    login.poll(active)?.let { return@launch connect(it) }
                    failures = 0
                } catch (e: CancellationException) {
                    throw e
                } catch (e: LoginFlowException) {
                    // A backgrounded app often has no network; that is not a failed login, so keep waiting.
                    val offline = e.cause is java.io.IOException
                    if (!offline && ++failures >= MAX_POLL_FAILURES) {
                        return@launch setNextcloud(NextcloudState.Error(e.message ?: "Login failed"))
                    }
                }
                if (waited >= LOGIN_TIMEOUT_MS) {
                    return@launch setNextcloud(NextcloudState.Error("Timed out waiting for login approval"))
                }
                delay(POLL_INTERVAL_MS)
                waited += POLL_INTERVAL_MS
            }
        }
    }

    private fun connect(credentials: NextcloudCredentials) {
        settings.saveNextcloudAccount(NextcloudAccount(credentials.server, credentials.loginName, credentials.appPassword))
        session = null
        setNextcloud(NextcloudState.Connected(credentials.loginName, settings.lastSyncMillis.value.orNull()))
        sync.syncNow()
    }

    private fun stopLogin() {
        loginJob?.cancel()
        loginJob = null
        session = null
    }

    private fun setNextcloud(state: NextcloudState) = _state.update { it.copy(nextcloud = state) }

    // --- PodcastIndex ------------------------------------------------------------------------

    private fun editForm(edit: (PodcastIndexForm) -> PodcastIndexForm) =
        _state.update { it.copy(podcastIndex = edit(it.podcastIndex)) }

    private fun savePodcastIndex() {
        val form = _state.value.podcastIndex
        val credentials = PodcastIndexCredentials(form.apiKey.trim(), form.apiSecret.trim())
        if (!credentials.isComplete) return
        settings.savePodcastIndexCredentials(credentials)
        editForm { it.copy(saved = true) }
    }

    private fun Long.orNull(): Long? = takeIf { it > 0 }

    private companion object {
        const val POLL_INTERVAL_MS = 2_000L
        const val LOGIN_TIMEOUT_MS = 20 * 60_000L
        const val MAX_POLL_FAILURES = 3
    }
}
