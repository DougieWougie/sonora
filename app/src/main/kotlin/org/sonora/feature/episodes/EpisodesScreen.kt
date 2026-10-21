// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.episodes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.sonora.feature.downloads.DownloadsLink
import org.sonora.ui.components.EpisodeActionsUi
import org.sonora.ui.components.MarkOlderPlayedDialog
import org.sonora.ui.components.SwipeableEpisodeRow

@Composable
fun EpisodesScreen(
    snackbar: SnackbarHostState,
    onOpenDownloads: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EpisodesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.events.collect { if (it is EpisodesEvent.Message) snackbar.showSnackbar(it.text) }
    }
    val actions = remember(viewModel) {
        EpisodeActionsUi(
            onPlay = { viewModel.onIntent(EpisodesIntent.Play(it.id)) },
            onToggleQueue = { viewModel.onIntent(if (it.queued) EpisodesIntent.Dequeue(it.id) else EpisodesIntent.Enqueue(it.id)) },
            onDownload = { viewModel.onIntent(EpisodesIntent.Download(it.id)) },
            onDeleteDownload = { viewModel.onIntent(EpisodesIntent.DeleteDownload(it.id)) },
            onSetPlayed = { item, played -> viewModel.onIntent(EpisodesIntent.SetPlayed(item.id, played)) },
            onMarkOlderPlayed = { viewModel.onIntent(EpisodesIntent.RequestMarkOlderPlayed(it.id)) },
        )
    }

    EpisodesContent(state, actions, viewModel::onIntent, modifier, downloadsLink = { DownloadsLink(onClick = onOpenDownloads) })
}

@Composable
internal fun EpisodesContent(
    state: EpisodesUiState,
    actions: EpisodeActionsUi,
    onIntent: (EpisodesIntent) -> Unit,
    modifier: Modifier = Modifier,
    downloadsLink: @Composable () -> Unit,
) {
    state.pendingMarkOlder?.let {
        MarkOlderPlayedDialog(
            it,
            onConfirm = { onIntent(EpisodesIntent.ConfirmMarkOlderPlayed) },
            onDismiss = { onIntent(EpisodesIntent.DismissMarkOlderPlayed) },
        )
    }

    if (state.isLoading) {
        Box(modifier.fillMaxSize(), Alignment.Center) { LoadingIndicator() }
        return
    }
    LazyColumn(modifier.fillMaxSize()) {
        item { Text("Episodes", style = MaterialTheme.typography.headlineLargeEmphasized, modifier = Modifier.padding(16.dp)) }
        item { downloadsLink() }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Up next", style = MaterialTheme.typography.titleMediumEmphasized)
                if (state.queue.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onIntent(EpisodesIntent.PlayQueue) }) { Icon(Icons.Rounded.PlayArrow, contentDescription = "Play queue") }
                        IconButton(onClick = { onIntent(EpisodesIntent.ShuffleQueue) }) { Icon(Icons.Rounded.Shuffle, contentDescription = "Shuffle queue") }
                        TextButton(onClick = { onIntent(EpisodesIntent.ClearQueue) }) { Text("Clear") }
                    }
                }
            }
        }
        if (state.queue.isEmpty()) {
            item { Text("Swipe an episode right to queue it.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(state.queue, key = { "q${it.id}" }) { SwipeableEpisodeRow(it, actions) }
        item { Text("Inbox", style = MaterialTheme.typography.titleMediumEmphasized, modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 4.dp)) }
        if (state.inbox.isEmpty()) {
            item { Text("You're all caught up.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(state.inbox, key = { "i${it.id}" }) { SwipeableEpisodeRow(it, actions) }
    }
}
