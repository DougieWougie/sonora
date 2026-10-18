// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
)

/**
 * Swipe right to queue, left to download (or remove the download). The row snaps back: the swipe
 * is a gesture, the state change shows up in the row itself.
 */
@Composable
fun SwipeableEpisodeRow(item: EpisodeItem, actions: EpisodeActionsUi, modifier: Modifier = Modifier) {
    val state = rememberSwipeToDismissBoxState()
    // Act on the swipe, then snap back: the state change shows up in the row itself.
    LaunchedEffect(state.currentValue) {
        when (state.currentValue) {
            SwipeToDismissBoxValue.StartToEnd -> actions.onToggleQueue(item)
            SwipeToDismissBoxValue.EndToStart ->
                if (item.downloadState == DownloadState.DOWNLOADED) actions.onDeleteDownload(item) else actions.onDownload(item)
            SwipeToDismissBoxValue.Settled -> return@LaunchedEffect
        }
        state.reset()
    }
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
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
        EpisodeRow(item, actions)
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
fun EpisodeRow(item: EpisodeItem, actions: EpisodeActionsUi, modifier: Modifier = Modifier) {
    var menuOpen by remember { mutableStateOf(false) }
    ListItem(
        modifier = modifier.clickable { actions.onPlay(item) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
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
        leadingContent = { ArtworkImage(item.artworkUrl, null, Modifier.size(56.dp)) },
        trailingContent = {
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More actions") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(if (item.queued) "Remove from queue" else "Add to queue") },
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
