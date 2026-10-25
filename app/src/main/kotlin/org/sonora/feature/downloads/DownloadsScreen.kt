// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.sonora.core.download.WaitReason
import org.sonora.core.model.DownloadState
import org.sonora.core.testing.TestTags
import org.sonora.feature.common.EpisodeItem
import org.sonora.ui.components.ArtworkImage
import org.sonora.ui.components.ScreenHeading
import org.sonora.ui.formatBytes
import org.sonora.ui.formatUsageSummary

@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.events.collect { if (it is DownloadsEvent.Message) snackbar.showSnackbar(it.text) }
    }
    DownloadsContent(state, onBack, viewModel::onIntent, modifier)
}

@Composable
fun DownloadsContent(
    state: DownloadsUiState,
    onBack: () -> Unit,
    onIntent: (DownloadsIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmingDeletePlayed by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.padding(start = 4.dp).testTag(TestTags.DOWNLOADS_BACK)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
            }
            ScreenHeading("Downloads")
        }

        if (state.isLoading) {
            Box(Modifier.fillMaxSize(), Alignment.Center) { LoadingIndicator() }
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize()) {
            if (state.evictedUnplayed > 0) {
                item { EvictionBanner(state.evictedUnplayed) { onIntent(DownloadsIntent.DismissEvictionNotice) } }
            }
            if (state.isEmpty) {
                item {
                    Text(
                        "Nothing downloaded yet. Turn on auto-download for a podcast, or download an episode from its page.",
                        modifier = Modifier.padding(16.dp).testTag(TestTags.DOWNLOADS_EMPTY),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@LazyColumn
            }

            item { UsageCard(state.usage, onDeletePlayed = { confirmingDeletePlayed = true }) }

            if (state.active.isNotEmpty()) {
                item { SectionHeader("Downloading (${state.active.size})") }
                items(state.active, key = { "a${it.item.id}" }) { ActiveRow(it, onIntent) }
            }
            if (state.failed.isNotEmpty()) {
                item { SectionHeader("Failed (${state.failed.size})") }
                items(state.failed, key = { "f${it.id}" }) { FailedRow(it, onIntent) }
            }
            if (state.downloaded.isNotEmpty()) {
                item { SectionHeader("Downloaded") }
                item { SortChips(state.sort, onIntent) }
                items(state.downloaded, key = { "d${it.id}" }) { DownloadedRow(it, onIntent) }
            }
        }
    }

    if (confirmingDeletePlayed) {
        val count = state.usage.playedCount
        AlertDialog(
            onDismissRequest = { confirmingDeletePlayed = false },
            text = {
                Text("Delete $count played download${if (count == 1) "" else "s"}? This frees ${formatBytes(state.usage.playedBytes)}.")
            },
            confirmButton = {
                TextButton(
                    onClick = { confirmingDeletePlayed = false; onIntent(DownloadsIntent.DeleteAllPlayed) },
                    modifier = Modifier.testTag(TestTags.DOWNLOADS_CONFIRM_DELETE_PLAYED),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmingDeletePlayed = false },
                    modifier = Modifier.testTag(TestTags.DOWNLOADS_DISMISS_DELETE_PLAYED),
                ) { Text("Cancel") }
            },
        )
    }
}

/** Retention removed episodes the user hadn't played; say so rather than let them vanish. */
@Composable
private fun EvictionBanner(count: Int, onDismiss: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag(TestTags.DOWNLOADS_EVICTION_BANNER),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (count == 1) "1 unplayed episode was removed to stay within your limits."
                else "$count unplayed episodes were removed to stay within your limits.",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(TestTags.DOWNLOADS_EVICTION_DISMISS)) { Text("Dismiss") }
        }
    }
}

@Composable
private fun UsageCard(usage: StorageUsage, onDeletePlayed: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                formatUsageSummary(usage),
                style = MaterialTheme.typography.titleMediumEmphasized,
                modifier = Modifier.testTag(TestTags.DOWNLOADS_USAGE_SUMMARY),
            )
            UsageBar(usage)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Legend(MaterialTheme.colorScheme.primary, "Unplayed ${formatBytes(usage.unplayedBytes)}")
                Legend(MaterialTheme.colorScheme.tertiary, "Played ${formatBytes(usage.playedBytes)}")
            }
            if (usage.playedCount > 0) {
                FilledTonalButton(onClick = onDeletePlayed, modifier = Modifier.testTag(TestTags.DOWNLOADS_DELETE_PLAYED_BUTTON)) {
                    Text("Delete all played · ${formatBytes(usage.playedBytes)}")
                }
            }
        }
    }
}

/** Unplayed then played, filling the limit if there is one, or the whole bar if there isn't. */
@Composable
private fun UsageBar(usage: StorageUsage) {
    val scale = maxOf(usage.limitBytes, usage.totalBytes).coerceAtLeast(1).toFloat()
    val unplayed = (usage.unplayedBytes / scale).coerceIn(0f, 1f)
    val played = (usage.playedBytes / scale).coerceIn(0f, 1f - unplayed)
    val free = (1f - unplayed - played).coerceAtLeast(0f)
    Row(
        Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (unplayed > 0f) Box(Modifier.weight(unplayed).fillMaxSize().background(MaterialTheme.colorScheme.primary))
        if (played > 0f) Box(Modifier.weight(played).fillMaxSize().background(MaterialTheme.colorScheme.tertiary))
        if (free > 0f) Box(Modifier.weight(free).fillMaxSize())
    }
}

@Composable
private fun Legend(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMediumEmphasized,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SortChips(selected: DownloadSort, onIntent: (DownloadsIntent) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DownloadSort.entries.forEach { sort ->
            FilterChip(
                selected = sort == selected,
                onClick = { onIntent(DownloadsIntent.SetSort(sort)) },
                label = { Text(when (sort) { DownloadSort.NEWEST -> "Newest"; DownloadSort.LARGEST -> "Largest"; DownloadSort.SHOW -> "Show" }) },
                modifier = Modifier.testTag(TestTags.downloadSort(sort.name)),
            )
        }
    }
}

@Composable
private fun ActiveRow(active: ActiveDownload, onIntent: (DownloadsIntent) -> Unit) {
    val item = active.item
    DownloadListItem(
        item = item,
        supporting = {
            if (item.downloadState == DownloadState.DOWNLOADING) {
                val total = item.sizeBytes?.takeIf { it > 0 }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (total != null) {
                        LinearProgressIndicator(progress = { (item.downloadedBytes.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Text("${formatBytes(item.downloadedBytes)} of ${formatBytes(total)}", style = MaterialTheme.typography.labelSmall)
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(formatBytes(item.downloadedBytes), style = MaterialTheme.typography.labelSmall)
                    }
                }
            } else {
                Text(waitText(active.waiting), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        },
        trailing = {
            IconButton(onClick = { onIntent(DownloadsIntent.Cancel(item.id)) }, modifier = Modifier.testTag(TestTags.downloadCancel(item.id))) {
                Icon(Icons.Rounded.Close, contentDescription = "Cancel download")
            }
        },
    )
}

@Composable
private fun FailedRow(item: EpisodeItem, onIntent: (DownloadsIntent) -> Unit) {
    DownloadListItem(
        item = item,
        supporting = { Text("Download failed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onIntent(DownloadsIntent.Retry(item.id)) }, modifier = Modifier.testTag(TestTags.downloadRetry(item.id))) {
                    Text("Retry")
                }
                IconButton(onClick = { onIntent(DownloadsIntent.Delete(item.id)) }, modifier = Modifier.testTag(TestTags.downloadDelete(item.id))) {
                    Icon(Icons.Rounded.Close, contentDescription = "Dismiss")
                }
            }
        },
    )
}

@Composable
private fun DownloadedRow(item: EpisodeItem, onIntent: (DownloadsIntent) -> Unit) {
    DownloadListItem(
        item = item,
        modifier = Modifier.clickable { onIntent(DownloadsIntent.Play(item.id)) },
        supporting = {
            Text(
                "${formatBytes(item.downloadedBytes)} · ${if (item.played) "Played" else "Unplayed"}",
                style = MaterialTheme.typography.labelSmall,
            )
        },
        trailing = {
            IconButton(onClick = { onIntent(DownloadsIntent.Delete(item.id)) }, modifier = Modifier.testTag(TestTags.downloadDelete(item.id))) {
                Icon(Icons.Rounded.Delete, contentDescription = "Delete download")
            }
        },
    )
}

@Composable
private fun DownloadListItem(
    item: EpisodeItem,
    supporting: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        modifier = modifier.testTag(TestTags.downloadRow(item.id)),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        overlineContent = item.podcastTitle?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        content = { Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMediumEmphasized) },
        supportingContent = supporting,
        leadingContent = { ArtworkImage(item.artworkUrl, null, Modifier.size(56.dp)) },
        trailingContent = trailing,
    )
}

private fun waitText(reason: WaitReason?) = when (reason) {
    WaitReason.WIFI -> "Waiting for Wi-Fi"
    WaitReason.CHARGER -> "Waiting for charger"
    WaitReason.CONNECTION -> "Waiting for a connection"
    null -> "Queued"
}
