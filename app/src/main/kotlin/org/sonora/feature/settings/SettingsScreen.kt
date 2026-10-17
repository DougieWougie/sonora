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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date
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

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            if (event is SettingsEvent.OpenLoginUrl && openInBrowser(context, event.url)) {
                viewModel.onIntent(SettingsIntent.BrowserLaunched)
            }
        }
    }

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
    }
}

@Composable
private fun NextcloudCard(state: SettingsUiState, onIntent: (SettingsIntent) -> Unit) {
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
                    supportingText = (nc as? NextcloudState.Error)?.let { { Text(it.message) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { onIntent(SettingsIntent.StartLogin(server)) }, enabled = server.isNotBlank()) { Text("Connect") }
            }
            is NextcloudState.WaitingForBrowserAuth -> {
                Text("Opening your browser to approve access…", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { onIntent(SettingsIntent.BrowserLaunched) }) { Text("I've opened it") }
                    TextButton(onClick = { onIntent(SettingsIntent.CancelLogin) }) { Text("Cancel") }
                }
            }
            NextcloudState.Polling -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    LoadingIndicator(Modifier.size(48.dp))
                    Text("Waiting for you to approve access in the browser…", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
                TextButton(onClick = { onIntent(SettingsIntent.CancelLogin) }) { Text("Cancel") }
            }
            is NextcloudState.Connected -> {
                Text("Connected as ${nc.username}", style = MaterialTheme.typography.titleMediumEmphasized)
                Text(
                    nc.lastSyncTime?.let { "Last synced ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))}" } ?: "Not synced yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { onIntent(SettingsIntent.SyncNow) }, enabled = !state.isSyncing) { Text("Sync now") }
                    if (state.isSyncing) LoadingIndicator(Modifier.size(40.dp))
                    OutlinedButton(onClick = { onIntent(SettingsIntent.Disconnect) }) { Text("Disconnect") }
                }
            }
        }
    }
}

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
