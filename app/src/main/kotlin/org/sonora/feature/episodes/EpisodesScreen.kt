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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import org.sonora.ui.components.QueueDragHandle
import org.sonora.ui.components.rememberReorderState
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
        viewModel.events.collect {
            when (it) {
                is EpisodesEvent.Message -> snackbar.showSnackbar(it.text)
                is EpisodesEvent.QueueChanged -> {
                    val result = snackbar.showSnackbar(it.text, actionLabel = "Undo", duration = SnackbarDuration.Long)
                    if (result == SnackbarResult.ActionPerformed) viewModel.onIntent(EpisodesIntent.RestoreQueue(it.previous))
                }
            }
        }
    }
    val actions = remember(viewModel) {
        EpisodeActionsUi(
            onPlay = { viewModel.onIntent(EpisodesIntent.Play(it.id)) },
            onPlayNext = { viewModel.onIntent(EpisodesIntent.PlayNext(it.id)) },
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

    if (state.confirmingClear) {
        val count = state.queue.size
        AlertDialog(
            onDismissRequest = { onIntent(EpisodesIntent.DismissClearQueue) },
            title = { Text("Clear $count ${if (count == 1) "episode" else "episodes"} from Up next?") },
            text = { Text("They stay in your library; only the queue is emptied.") },
            confirmButton = { TextButton(onClick = { onIntent(EpisodesIntent.ClearQueue) }) { Text("Clear queue") } },
            dismissButton = { TextButton(onClick = { onIntent(EpisodesIntent.DismissClearQueue) }) { Text("Cancel") } },
        )
    }

    if (state.isLoading) {
        Box(modifier.fillMaxSize(), Alignment.Center) { LoadingIndicator() }
        return
    }
    val listState = rememberLazyListState()
    val queueKeys = state.queue.map { "q${it.id}" }
    val reorder = rememberReorderState(listState, { queueKeys }, { from, to -> onIntent(EpisodesIntent.MoveInQueue(state.queue[from].id, to)) })
    LazyColumn(modifier.fillMaxSize(), state = listState) {
        item { Text("Episodes", style = MaterialTheme.typography.headlineLargeEmphasized, modifier = Modifier.padding(16.dp)) }
        item { downloadsLink() }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Up next", style = MaterialTheme.typography.titleMediumEmphasized)
                if (state.queue.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onIntent(EpisodesIntent.PlayQueue) }) { Icon(Icons.Rounded.PlayArrow, contentDescription = "Play queue") }
                        IconButton(onClick = { onIntent(EpisodesIntent.ShuffleQueue) }) { Icon(Icons.Rounded.Shuffle, contentDescription = "Shuffle queue") }
                        TextButton(onClick = { onIntent(EpisodesIntent.RequestClearQueue) }) { Text("Clear") }
                    }
                }
            }
        }
        if (state.queue.isEmpty()) {
            item { Text("Swipe an episode right to queue it.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        itemsIndexed(state.queue, key = { _, it -> "q${it.id}" }) { index, episode ->
            val key = "q${episode.id}"
            with(reorder) {
                Row(
                    Modifier.reorderable(key).let { if (key == draggedKey) it else it.animateItem() },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    QueueDragHandle(
                        title = episode.title,
                        key = key,
                        state = reorder,
                        canMoveUp = index > 0,
                        canMoveDown = index < state.queue.lastIndex,
                        onMoveUp = { onIntent(EpisodesIntent.MoveInQueue(episode.id, index - 1)) },
                        onMoveDown = { onIntent(EpisodesIntent.MoveInQueue(episode.id, index + 1)) },
                    )
                    SwipeableEpisodeRow(episode, actions, Modifier.weight(1f))
                }
            }
        }
        item { Text("Inbox", style = MaterialTheme.typography.titleMediumEmphasized, modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 4.dp)) }
        if (state.inbox.isEmpty()) {
            item { Text("You're all caught up.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(state.inbox, key = { "i${it.id}" }) { SwipeableEpisodeRow(it, actions) }
    }
}
