// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.PlaylistRemove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import org.sonora.core.model.DownloadState
import org.sonora.feature.common.EpisodeItem
import org.sonora.feature.common.PendingMarkOlder
import org.sonora.ui.formatTimestamp

data class EpisodeActionsUi(
    val onPlay: (EpisodeItem) -> Unit,
    val onToggleQueue: (EpisodeItem) -> Unit,
    val onDownload: (EpisodeItem) -> Unit,
    val onDeleteDownload: (EpisodeItem) -> Unit,
    val onSetPlayed: (EpisodeItem, Boolean) -> Unit,
    val onMarkOlderPlayed: (EpisodeItem) -> Unit,
    val onPlayNext: (EpisodeItem) -> Unit = {},
)

/** Bulk-selection state for one row: [active] while any row is selected, so a tap toggles instead of playing. */
data class EpisodeSelection(val active: Boolean, val selected: Boolean, val onToggle: () -> Unit)

/**
 * Swipe right to queue, left to download (or remove the download). The row snaps back: the swipe
 * is a gesture, the state change shows up in the row itself.
 */
@Composable
fun SwipeableEpisodeRow(item: EpisodeItem, actions: EpisodeActionsUi, modifier: Modifier = Modifier, selection: EpisodeSelection? = null) {
    SwipeActionRow(
        item = item,
        onSwipeRight = { actions.onToggleQueue(it) },
        onSwipeLeft = { if (it.downloadState == DownloadState.DOWNLOADED) actions.onDeleteDownload(it) else actions.onDownload(it) },
        modifier = modifier,
        enabled = selection?.active != true,
    ) {
        EpisodeRow(item, actions, selection = selection)
    }
}

/**
 * The swipe behaviour on its own, for lists that draw their own rows: [onSwipeRight] and
 * [onSwipeLeft] get the item as it is when the swipe lands, and the backdrop shows what they do.
 */
@Composable
fun SwipeActionRow(
    item: EpisodeItem,
    onSwipeRight: (EpisodeItem) -> Unit,
    onSwipeLeft: (EpisodeItem) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val currentItem by rememberUpdatedState(item)
    val currentRight by rememberUpdatedState(onSwipeRight)
    val currentLeft by rememberUpdatedState(onSwipeLeft)
    val state = rememberSwipeToDismissBoxState()
    // Act once per swipe, then animate back to rest: the state change shows up in the row itself.
    LaunchedEffect(state.currentValue) {
        when (state.currentValue) {
            SwipeToDismissBoxValue.StartToEnd -> currentRight(currentItem)
            SwipeToDismissBoxValue.EndToStart -> currentLeft(currentItem)
            SwipeToDismissBoxValue.Settled -> return@LaunchedEffect
        }
        state.snapTo(SwipeToDismissBoxValue.Settled)
    }
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = enabled,
        enableDismissFromEndToStart = enabled,
        backgroundContent = {
            val style = swipeActionStyle(state.dismissDirection, item, MaterialTheme.colorScheme)
            Box(
                Modifier.fillMaxSize().background(style.container).padding(horizontal = 24.dp),
                contentAlignment = style.alignment,
            ) {
                if (style.icon != null) Icon(style.icon, contentDescription = null, tint = style.content)
            }
        },
    ) {
        content()
    }
}

internal data class SwipeActionStyle(
    val icon: ImageVector?,
    val alignment: Alignment,
    val container: Color,
    val content: Color,
)

/** Queue is primary, download is tertiary and deleting a download is error, so each swipe reads at a glance. */
internal fun swipeActionStyle(direction: SwipeToDismissBoxValue, item: EpisodeItem, scheme: ColorScheme): SwipeActionStyle =
    when (direction) {
        SwipeToDismissBoxValue.StartToEnd -> SwipeActionStyle(
            if (item.queued) Icons.Rounded.PlaylistRemove else Icons.AutoMirrored.Rounded.PlaylistAdd,
            Alignment.CenterStart, scheme.primaryContainer, scheme.onPrimaryContainer,
        )
        SwipeToDismissBoxValue.EndToStart ->
            if (item.downloadState == DownloadState.DOWNLOADED) {
                SwipeActionStyle(Icons.Rounded.Delete, Alignment.CenterEnd, scheme.errorContainer, scheme.onErrorContainer)
            } else {
                SwipeActionStyle(Icons.Rounded.Download, Alignment.CenterEnd, scheme.tertiaryContainer, scheme.onTertiaryContainer)
            }
        SwipeToDismissBoxValue.Settled -> SwipeActionStyle(null, Alignment.Center, Color.Transparent, Color.Transparent)
    }

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun EpisodeRow(item: EpisodeItem, actions: EpisodeActionsUi, modifier: Modifier = Modifier, selection: EpisodeSelection? = null) {
    var menuOpen by remember { mutableStateOf(false) }
    ListItem(
        modifier = if (selection == null) modifier.clickable { actions.onPlay(item) } else modifier.combinedClickable(
            onClick = { if (selection.active) selection.onToggle() else actions.onPlay(item) },
            onLongClickLabel = "Select",
            onLongClick = selection.onToggle,
        ).semantics { this.selected = selection.selected },
        colors = ListItemDefaults.colors(
            containerColor = if (selection?.selected == true) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        ),
        overlineContent = item.podcastTitle?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        content = {
            Text(
                item.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMediumEmphasized,
                fontWeight = if (item.played) FontWeight.Normal else null,
                color = if (item.played) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        },
        supportingContent = { EpisodeSubtitle(item) },
        leadingContent = {
            Box {
                ArtworkImage(item.artworkUrl, null, Modifier.size(56.dp))
                if (selection?.selected == true) {
                    Icon(
                        Icons.Rounded.CheckCircle, "Selected",
                        Modifier.align(Alignment.Center).size(28.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More actions") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Play next") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, null) },
                        onClick = { menuOpen = false; actions.onPlayNext(item) },
                    )
                    DropdownMenuItem(
                        text = { Text(if (item.queued) "Remove from queue" else "Add to end of queue") },
                        leadingIcon = { Icon(if (item.queued) Icons.Rounded.PlaylistRemove else Icons.AutoMirrored.Rounded.PlaylistAdd, null) },
                        onClick = { menuOpen = false; actions.onToggleQueue(item) },
                    )
                    if (item.downloadState == DownloadState.DOWNLOADED) {
                        DropdownMenuItem(
                            text = { Text("Delete download") },
                            leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                            onClick = { menuOpen = false; actions.onDeleteDownload(item) },
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text("Download") },
                            leadingIcon = { Icon(Icons.Rounded.Download, null) },
                            onClick = { menuOpen = false; actions.onDownload(item) },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(if (item.played) "Mark as unplayed" else "Mark as played") },
                        leadingIcon = { Icon(Icons.Rounded.CheckCircle, null) },
                        onClick = { menuOpen = false; actions.onSetPlayed(item, !item.played) },
                    )
                    DropdownMenuItem(
                        text = { Text("Mark older episodes as played") },
                        leadingIcon = { Icon(Icons.Rounded.DoneAll, null) },
                        onClick = { menuOpen = false; actions.onMarkOlderPlayed(item) },
                    )
                }
            }
        },
    )
}

@Composable
private fun EpisodeSubtitle(item: EpisodeItem) {
    val parts = buildList {
        item.publishedAtMillis?.let { add(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it))) }
        item.durationSeconds?.let { add(formatTimestamp(it * 1000)) }
        if (item.positionSeconds > 0 && !item.played) add("${formatTimestamp(item.positionSeconds * 1000)} in")
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(parts.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        when (item.downloadState) {
            DownloadState.DOWNLOADED -> Icon(Icons.Rounded.DownloadDone, "Downloaded", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            DownloadState.QUEUED, DownloadState.DOWNLOADING -> Text("Downloading…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            DownloadState.FAILED -> Text("Download failed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            DownloadState.NONE -> Unit
        }
        if (item.queued) Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "In queue", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
    }
}

/** Bulk and hard to undo, so the user sees how many episodes it covers before anything changes. */
@Composable
fun MarkOlderPlayedDialog(pending: PendingMarkOlder, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val none = pending.count == 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (none) "No older unplayed episodes"
                else "Mark ${pending.count} older ${if (pending.count == 1) "episode" else "episodes"} as played?",
            )
        },
        text = if (none) null else ({ Text("Episodes published before this one will be marked as played and synced.") }),
        confirmButton = if (none) ({ TextButton(onClick = onDismiss) { Text("OK") } }) else ({ TextButton(onClick = onConfirm) { Text("Mark as played") } }),
        dismissButton = if (none) null else ({ TextButton(onClick = onDismiss) { Text("Cancel") } }),
    )
}
