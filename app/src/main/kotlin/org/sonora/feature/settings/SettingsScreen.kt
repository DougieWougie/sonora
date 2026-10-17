// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.flow.Flow
import org.sonora.core.testing.TestTags
import org.sonora.ui.components.SonoraLogo
import org.sonora.ui.theme.ThemeIntent
import org.sonora.ui.theme.ThemeMode
import org.sonora.ui.theme.ThemeViewModel

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsAndSyncViewModel = hiltViewModel(),
    themeViewModel: ThemeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val theme by themeViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LifecycleResumeEffect(viewModel) {
        viewModel.onIntent(SettingsIntent.ReturnedToApp)
        onPauseOrDispose {}
    }

    OpenLoginUrlEffect(viewModel.events, open = { openInBrowser(context, it) }, onLaunched = { viewModel.onIntent(SettingsIntent.BrowserLaunched) })

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings & Sync", style = MaterialTheme.typography.headlineLargeEmphasized)
        NextcloudCard(state, viewModel::onIntent)

        SettingsCard("Appearance") {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = theme.preferences.mode == mode,
                        onClick = { themeViewModel.onIntent(ThemeIntent.SelectMode(mode)) },
                        label = { Text(when (mode) { ThemeMode.SYSTEM -> "System"; ThemeMode.LIGHT -> "Light"; ThemeMode.DARK -> "Dark"; ThemeMode.AMOLED -> "AMOLED" }) },
                    )
                }
            }
            SwitchRow("Colors from artwork", "Tint the app with the playing episode's cover", theme.preferences.artworkColors) {
                themeViewModel.onIntent(ThemeIntent.ToggleArtworkColors)
            }
        }

        SettingsCard("Downloads") {
            SwitchRow("Auto-download on Wi-Fi only", null, state.downloads.unmeteredOnly) { viewModel.onIntent(SettingsIntent.SetUnmeteredOnly(it)) }
            SwitchRow("Delete downloads once played", null, state.downloads.deleteWhenPlayed) { viewModel.onIntent(SettingsIntent.SetDeleteWhenPlayed(it)) }
        }

        SettingsCard("PodcastIndex") {
            Text("Optional. Add your free PodcastIndex API key to search it in Discover.", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = state.podcastIndex.apiKey,
                onValueChange = { viewModel.onIntent(SettingsIntent.EditPodcastIndexKey(it)) },
                label = { Text("API key") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.podcastIndex.apiSecret,
                onValueChange = { viewModel.onIntent(SettingsIntent.EditPodcastIndexSecret(it)) },
                label = { Text("API secret") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { viewModel.onIntent(SettingsIntent.SavePodcastIndex) }, enabled = !state.podcastIndex.saved) {
                    Text(if (state.podcastIndex.saved) "Saved" else "Save")
                }
                TextButton(onClick = { viewModel.onIntent(SettingsIntent.ClearPodcastIndex) }) { Text("Remove") }
            }
        }

        SettingsCard("Advanced") {
            SwitchRow(
                "Allow cleartext (HTTP)",
                "For testing against servers on your own network. Streams, feeds and sync stay HTTPS-only otherwise.",
                state.allowCleartext,
            ) { viewModel.onIntent(SettingsIntent.SetAllowCleartext(it)) }
            if (state.restartRequired) {
                Text("Restart Sonora for this change to take effect.", color = MaterialTheme.colorScheme.tertiary)
            }
        }

        AboutCard(versionName(context))
    }
}

/** Opens each login URL the view model asks for, and tells it once a browser took it so polling can begin. */
@Composable
internal fun OpenLoginUrlEffect(events: Flow<SettingsEvent>, open: (String) -> Boolean, onLaunched: () -> Unit) {
    val currentOpen by rememberUpdatedState(open)
    val currentOnLaunched by rememberUpdatedState(onLaunched)
    LaunchedEffect(events) {
        events.collect { event ->
            if (event is SettingsEvent.OpenLoginUrl && currentOpen(event.url)) currentOnLaunched()
        }
    }
}

@Composable
internal fun NextcloudCard(state: SettingsUiState, onIntent: (SettingsIntent) -> Unit) {
    var server by remember { mutableStateOf("") }
    SettingsCard("Nextcloud GPodder Sync") {
        when (val nc = state.nextcloud) {
            NextcloudState.Idle, is NextcloudState.Error -> {
                Text("Sync subscriptions and progress with your Nextcloud server. Sonora never sees your password; you approve access in the browser.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it },
                    label = { Text("Server, e.g. cloud.example.com") },
                    singleLine = true,
                    isError = nc is NextcloudState.Error,
                    modifier = Modifier.fillMaxWidth().testTag(TestTags.NEXTCLOUD_SERVER_INPUT),
                )
                if (nc is NextcloudState.Error) {
                    ErrorBanner(nc.message, canRetry = server.isNotBlank()) { onIntent(SettingsIntent.StartLogin(server)) }
                }
                Button(
                    onClick = { onIntent(SettingsIntent.StartLogin(server)) },
                    enabled = server.isNotBlank(),
                    modifier = Modifier.testTag(TestTags.NEXTCLOUD_CONNECT_BUTTON),
                ) { Text("Connect") }
            }
            is NextcloudState.WaitingForBrowserAuth -> {
                Text("Opening your browser. Approve access there, then switch back to Sonora.", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { onIntent(SettingsIntent.BrowserLaunched) }) { Text("I've opened it") }
                    TextButton(onClick = { onIntent(SettingsIntent.CancelLogin) }, modifier = Modifier.testTag(TestTags.NEXTCLOUD_CANCEL_POLLING_BUTTON)) { Text("Cancel") }
                }
            }
            NextcloudState.Polling -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    LoadingIndicator(Modifier.size(48.dp).testTag(TestTags.NEXTCLOUD_POLLING_INDICATOR))
                    Text("Approve access in the browser, then switch back to Sonora.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
                TextButton(onClick = { onIntent(SettingsIntent.CancelLogin) }, modifier = Modifier.testTag(TestTags.NEXTCLOUD_CANCEL_POLLING_BUTTON)) { Text("Cancel") }
            }
            is NextcloudState.Connected -> {
                Text(
                    "${nc.username}@${nc.serverUrl.toHttpUrlOrNull()?.host ?: nc.serverUrl}",
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    modifier = Modifier.testTag(TestTags.NEXTCLOUD_CONNECTED_BADGE),
                )
                Text(
                    nc.lastSyncTime?.let { "Last synced ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))}" } ?: "Not synced yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { onIntent(SettingsIntent.SyncNow) }, enabled = !state.isSyncing, modifier = Modifier.testTag(TestTags.NEXTCLOUD_SYNC_NOW_BUTTON)) { Text("Sync now") }
                    if (state.isSyncing) LoadingIndicator(Modifier.size(40.dp))
                    OutlinedButton(onClick = { onIntent(SettingsIntent.Disconnect) }) { Text("Disconnect") }
                }
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String, canRetry: Boolean, onRetry: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().testTag(TestTags.NEXTCLOUD_ERROR_BANNER)) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            TextButton(onClick = onRetry, enabled = canRetry, modifier = Modifier.testTag(TestTags.NEXTCLOUD_RETRY_BUTTON)) { Text("Retry") }
        }
    }
}

@Composable
private fun AboutCard(version: String?) {
    SettingsCard("About") {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SonoraLogo(Modifier.fillMaxWidth(0.7f))
            version?.let { Text("Version $it", style = MaterialTheme.typography.bodyMedium) }
            Text("Free software under the GPL-3.0-or-later", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun versionName(context: Context): String? =
    try { context.packageManager.getPackageInfo(context.packageName, 0).versionName } catch (_: Exception) { null }

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMediumEmphasized)
            content()
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Opens [url] in a Custom Tab (or the default browser). Only web addresses are ever launched. */
private fun openInBrowser(context: Context, url: String): Boolean {
    val uri = Uri.parse(url)
    if (uri.scheme != "https" && uri.scheme != "http") return false
    return try {
        CustomTabsIntent.Builder().build().launchUrl(context, uri)
        true
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
