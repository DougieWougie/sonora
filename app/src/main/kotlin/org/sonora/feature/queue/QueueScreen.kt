// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.queue

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.getValue
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.sonora.ui.components.EpisodeActionsUi
import org.sonora.ui.components.MarkOlderPlayedDialog
import org.sonora.ui.components.PlayChoiceDialog
import org.sonora.ui.components.QueueDragHandle
import org.sonora.ui.components.rememberReorderState
import org.sonora.ui.components.SwipeableEpisodeRow

@Composable
internal fun QueueContent(
    state: QueueUiState,
    actions: EpisodeActionsUi,
    onIntent: (QueueIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    state.pendingMarkOlder?.let {
        MarkOlderPlayedDialog(
            it,
            onConfirm = { onIntent(QueueIntent.ConfirmMarkOlderPlayed) },
            onDismiss = { onIntent(QueueIntent.DismissMarkOlderPlayed) },
        )
    }

    if (state.confirmingClear) {
        val count = state.queue.size
        AlertDialog(
            onDismissRequest = { onIntent(QueueIntent.DismissClearQueue) },
            title = { Text("Clear $count ${if (count == 1) "episode" else "episodes"} from Queue?") },
            text = { Text("They stay in your library; only the queue is emptied.") },
            confirmButton = { TextButton(onClick = { onIntent(QueueIntent.ClearQueue) }) { Text("Clear queue") } },
            dismissButton = { TextButton(onClick = { onIntent(QueueIntent.DismissClearQueue) }) { Text("Cancel") } },
        )
    }

    if (state.isLoading) {
        Box(modifier.fillMaxSize(), Alignment.Center) { LoadingIndicator() }
        return
    }
    // Tapping an episode that is already lined up plays it; there is nowhere to ask it to go.
    val queuedActions = remember(actions) { actions.copy(onPlay = { onIntent(QueueIntent.PlayQueued(it.id)) }) }
    val listState = rememberLazyListState()
    val queueKeys = state.queue.map { "q${it.id}" }
    val reorder = rememberReorderState(listState, { queueKeys }, { from, to -> onIntent(QueueIntent.MoveInQueue(state.queue[from].id, to)) })
    LazyColumn(modifier.fillMaxSize(), state = listState) {
        if (state.queue.isNotEmpty()) {
            item { QueueActions(state.queue.size, onIntent) }
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
                        onMoveUp = { onIntent(QueueIntent.MoveInQueue(episode.id, index - 1)) },
                        onMoveDown = { onIntent(QueueIntent.MoveInQueue(episode.id, index + 1)) },
                    )
                    SwipeableEpisodeRow(episode, queuedActions, Modifier.weight(1f))
                }
            }
        }
    }
}

/** Unplayed episodes across every subscription, newest first. */
@Composable
internal fun NewEpisodesContent(
    state: QueueUiState,
    actions: EpisodeActionsUi,
    onIntent: (QueueIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    state.pendingMarkOlder?.let {
        MarkOlderPlayedDialog(
            it,
            onConfirm = { onIntent(QueueIntent.ConfirmMarkOlderPlayed) },
            onDismiss = { onIntent(QueueIntent.DismissMarkOlderPlayed) },
        )
    }
    if (state.pendingPlay != null) {
        PlayChoiceDialog(
            onPlayNext = { onIntent(QueueIntent.ConfirmPlayNext) },
            onAddToEnd = { onIntent(QueueIntent.ConfirmAddToEnd) },
            onDismiss = { onIntent(QueueIntent.DismissPlayChoice) },
        )
    }
    if (state.isLoading) {
        Box(modifier.fillMaxSize(), Alignment.Center) { LoadingIndicator() }
        return
    }
    LazyColumn(modifier.fillMaxSize()) {
        if (state.new.isEmpty()) {
            item { Text("You're all caught up.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(state.new, key = { "n${it.id}" }) { SwipeableEpisodeRow(it, actions, Modifier.animateItem()) }
    }
}

/** How many episodes are queued, with the actions that apply to the queue as a whole. */
@Composable
private fun QueueActions(count: Int, onIntent: (QueueIntent) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$count in queue",
            Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(onClick = { onIntent(QueueIntent.PlayQueue) }) { Icon(Icons.Rounded.PlayArrow, contentDescription = "Play queue") }
        IconButton(onClick = { onIntent(QueueIntent.ShuffleQueue) }) { Icon(Icons.Rounded.Shuffle, contentDescription = "Shuffle queue") }
        TextButton(onClick = { onIntent(QueueIntent.RequestClearQueue) }) { Text("Clear") }
    }
}
