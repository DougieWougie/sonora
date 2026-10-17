// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.ui.components

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
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PlaylistRemove
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
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import org.sonora.core.model.DownloadState
import org.sonora.feature.common.EpisodeItem
import org.sonora.ui.formatTimestamp

data class EpisodeActionsUi(
    val onPlay: (EpisodeItem) -> Unit,
    val onToggleQueue: (EpisodeItem) -> Unit,
    val onDownload: (EpisodeItem) -> Unit,
    val onDeleteDownload: (EpisodeItem) -> Unit,
    val onSetPlayed: (EpisodeItem, Boolean) -> Unit,
)

/**
 * Swipe right to queue, left to download (or remove the download). The row snaps back: the swipe
 * is a gesture, the state change shows up in the row itself.
 */
@Composable
fun SwipeableEpisodeRow(item: EpisodeItem, actions: EpisodeActionsUi, modifier: Modifier = Modifier) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> actions.onToggleQueue(item)
                SwipeToDismissBoxValue.EndToStart ->
                    if (item.downloadState == DownloadState.DOWNLOADED) actions.onDeleteDownload(item) else actions.onDownload(item)
                SwipeToDismissBoxValue.Settled -> Unit
            }
            false
        },
    )
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        backgroundContent = {
            val direction = state.dismissDirection
            val (icon, alignment) = when (direction) {
                SwipeToDismissBoxValue.StartToEnd ->
                    (if (item.queued) Icons.Rounded.PlaylistRemove else Icons.Rounded.PlaylistAdd) to Alignment.CenterStart
                SwipeToDismissBoxValue.EndToStart ->
                    (if (item.downloadState == DownloadState.DOWNLOADED) Icons.Rounded.Delete else Icons.Rounded.Download) to Alignment.CenterEnd
                SwipeToDismissBoxValue.Settled -> null to Alignment.Center
            }
            Box(
                Modifier.fillMaxSize().padding(horizontal = 24.dp),
                contentAlignment = alignment,
            ) {
                if (icon != null) Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        },
    ) {
        EpisodeRow(item, actions)
    }
}

@Composable
fun EpisodeRow(item: EpisodeItem, actions: EpisodeActionsUi, modifier: Modifier = Modifier) {
    var menuOpen by remember { mutableStateOf(false) }
    ListItem(
        modifier = modifier.clickable { actions.onPlay(item) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        overlineContent = item.podcastTitle?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        headlineContent = {
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
                        leadingIcon = { Icon(if (item.queued) Icons.Rounded.PlaylistRemove else Icons.Rounded.PlaylistAdd, null) },
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
        if (item.queued) Icon(Icons.Rounded.PlaylistAdd, "In queue", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
    }
}
