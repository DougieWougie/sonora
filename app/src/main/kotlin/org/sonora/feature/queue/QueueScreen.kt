// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.queue

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.sonora.feature.downloads.DownloadsLink
import org.sonora.ui.components.EpisodeActionsUi
import org.sonora.ui.components.MarkOlderPlayedDialog
import org.sonora.ui.components.QueueDragHandle
import org.sonora.ui.components.rememberReorderState
import org.sonora.ui.components.SwipeableEpisodeRow

@Composable
fun QueueScreen(
    snackbar: SnackbarHostState,
    onOpenDownloads: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: QueueViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.events.collect {
            when (it) {
                is QueueEvent.Message -> snackbar.showSnackbar(it.text)
                is QueueEvent.QueueChanged -> {
                    val result = snackbar.showSnackbar(it.text, actionLabel = "Undo", duration = SnackbarDuration.Long)
                    if (result == SnackbarResult.ActionPerformed) viewModel.onIntent(QueueIntent.RestoreQueue(it.previous))
                }
            }
        }
    }
    val actions = remember(viewModel) {
        EpisodeActionsUi(
            onPlay = { viewModel.onIntent(QueueIntent.Play(it.id)) },
            onPlayNext = { viewModel.onIntent(QueueIntent.PlayNext(it.id)) },
            onToggleQueue = { viewModel.onIntent(if (it.queued) QueueIntent.Dequeue(it.id) else QueueIntent.Enqueue(it.id)) },
            onDownload = { viewModel.onIntent(QueueIntent.Download(it.id)) },
            onDeleteDownload = { viewModel.onIntent(QueueIntent.DeleteDownload(it.id)) },
            onSetPlayed = { item, played -> viewModel.onIntent(QueueIntent.SetPlayed(item.id, played)) },
            onMarkOlderPlayed = { viewModel.onIntent(QueueIntent.RequestMarkOlderPlayed(it.id)) },
        )
    }

    QueueContent(state, actions, viewModel::onIntent, modifier, downloadsLink = { DownloadsLink(onClick = onOpenDownloads) })
}

@Composable
internal fun QueueContent(
    state: QueueUiState,
    actions: EpisodeActionsUi,
    onIntent: (QueueIntent) -> Unit,
    modifier: Modifier = Modifier,
    downloadsLink: @Composable () -> Unit,
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
    val listState = rememberLazyListState()
    val queueKeys = if (state.queueExpanded) state.queue.map { "q${it.id}" } else emptyList()
    val reorder = rememberReorderState(listState, { queueKeys }, { from, to -> onIntent(QueueIntent.MoveInQueue(state.queue[from].id, to)) })
    LazyColumn(modifier.fillMaxSize(), state = listState) {
        item { downloadsLink() }
        item {
            SectionHeader(
                title = "Queue",
                count = state.queue.size,
                expanded = state.queueExpanded,
                onToggle = { onIntent(QueueIntent.ToggleSection(QueueSection.QUEUE)) },
            ) {
                if (state.queue.isNotEmpty()) {
                    IconButton(onClick = { onIntent(QueueIntent.PlayQueue) }) { Icon(Icons.Rounded.PlayArrow, contentDescription = "Play queue") }
                    IconButton(onClick = { onIntent(QueueIntent.ShuffleQueue) }) { Icon(Icons.Rounded.Shuffle, contentDescription = "Shuffle queue") }
                    TextButton(onClick = { onIntent(QueueIntent.RequestClearQueue) }) { Text("Clear") }
                }
            }
        }
        if (state.queueExpanded) {
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
                        SwipeableEpisodeRow(episode, actions, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * A tonal bar naming a foldable section. The chevron is the one control TalkBack sees; tapping the
 * title is a bigger target for the same action. [actions] stay put while the section is folded.
 */
@Composable
private fun SectionHeader(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "chevron",
    )
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).heightIn(min = 48.dp).pointerInput(onToggle) { detectTapGestures { onToggle() } },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleMediumEmphasized, modifier = Modifier.semantics { heading() })
                Text(count.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            actions()
            IconButton(onClick = onToggle) {
                Icon(
                    Icons.Rounded.ExpandMore,
                    contentDescription = "${if (expanded) "Collapse" else "Expand"} $title",
                    modifier = Modifier.rotate(rotation),
                )
            }
        }
    }
}
