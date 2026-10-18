// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.podcast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.sonora.core.testing.TestTags
import org.sonora.ui.components.ArtworkImage
import org.sonora.ui.components.EpisodeActionsUi
import org.sonora.ui.components.MarkOlderPlayedDialog
import org.sonora.ui.components.SwipeableEpisodeRow
import org.sonora.ui.htmlToPlainText

@Composable
fun PodcastDetailScreen(
    onBack: () -> Unit,
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
    viewModel: PodcastDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.events.collect { if (it is PodcastDetailEvent.Message) snackbar.showSnackbar(it.text) }
    }
    val actions = remember(viewModel) {
        EpisodeActionsUi(
            onPlay = { viewModel.onIntent(PodcastDetailIntent.Play(it.id)) },
            onToggleQueue = { viewModel.onIntent(if (it.queued) PodcastDetailIntent.Dequeue(it.id) else PodcastDetailIntent.Enqueue(it.id)) },
            onDownload = { viewModel.onIntent(PodcastDetailIntent.Download(it.id)) },
            onDeleteDownload = { viewModel.onIntent(PodcastDetailIntent.DeleteDownload(it.id)) },
            onSetPlayed = { item, played -> viewModel.onIntent(PodcastDetailIntent.SetPlayed(item.id, played)) },
            onMarkOlderPlayed = { viewModel.onIntent(PodcastDetailIntent.RequestMarkOlderPlayed(it.id)) },
        )
    }

    state.pendingMarkOlder?.let {
        MarkOlderPlayedDialog(
            it,
            onConfirm = { viewModel.onIntent(PodcastDetailIntent.ConfirmMarkOlderPlayed) },
            onDismiss = { viewModel.onIntent(PodcastDetailIntent.DismissMarkOlderPlayed) },
        )
    }

    Column(modifier.fillMaxSize()) {
        // The 48dp button centres its 24dp icon 12dp in; 4dp more puts the arrow on the 16dp content edge.
        IconButton(onClick = onBack, modifier = Modifier.padding(start = 4.dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { viewModel.onIntent(PodcastDetailIntent.Refresh) },
            modifier = Modifier.weight(1f),
        ) {
            LazyColumn(Modifier.fillMaxSize()) {
                item { Header(state, viewModel::onIntent) }
                item { Controls(state, viewModel::onIntent) }
                if (state.isLoading) {
                    item { Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { LoadingIndicator() } }
                } else if (state.episodes.isEmpty()) {
                    item {
                        Text(
                            if (!state.subscribed) "Subscribe to see episodes." else "No episodes match.",
                            modifier = Modifier.padding(24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(state.episodes, key = { it.id }) { SwipeableEpisodeRow(it, actions) }
            }
        }
    }
}

@Composable
private fun Header(state: PodcastDetailUiState, onIntent: (PodcastDetailIntent) -> Unit) {
    val podcast = state.podcast ?: return
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ArtworkImage(podcast.imageUrl, podcast.title, Modifier.size(120.dp))
            Column(Modifier.weight(1f)) {
                Text(podcast.title, style = MaterialTheme.typography.headlineSmallEmphasized, maxLines = 3, overflow = TextOverflow.Ellipsis)
                podcast.author?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        podcast.description?.let { DescriptionPreview(it) }
        SubscribeSplitButton(state, onIntent)
    }
}

/** "Subscribe / Options": the leading half toggles the subscription, the trailing half opens more options. */
@Composable
private fun SubscribeSplitButton(state: PodcastDetailUiState, onIntent: (PodcastDetailIntent) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        SplitButtonLayout(
            leadingButton = {
                SplitButtonDefaults.LeadingButton(onClick = { onIntent(PodcastDetailIntent.ToggleSubscription) }) {
                    if (state.subscribed) Icon(Icons.Rounded.Check, null, Modifier.size(SplitButtonDefaults.LeadingIconSize))
                    Text(if (state.subscribed) "Subscribed" else "Subscribe", Modifier.padding(start = if (state.subscribed) 8.dp else 0.dp))
                }
            },
            trailingButton = {
                SplitButtonDefaults.TrailingButton(
                    checked = menuOpen,
                    onCheckedChange = { menuOpen = it },
                    enabled = state.subscribed,
                ) {
                    Icon(Icons.Rounded.ArrowDropDown, contentDescription = "Options", modifier = Modifier.size(SplitButtonDefaults.TrailingIconSize))
                }
            },
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            val auto = state.podcast?.autoDownload == true
            DropdownMenuItem(
                text = { Text(if (auto) "Stop auto-downloading new episodes" else "Auto-download new episodes") },
                onClick = { menuOpen = false; onIntent(PodcastDetailIntent.ToggleAutoDownload) },
            )
            DropdownMenuItem(
                text = { Text("Refresh now") },
                onClick = { menuOpen = false; onIntent(PodcastDetailIntent.Refresh) },
            )
        }
    }
}

@Composable
private fun Controls(state: PodcastDetailUiState, onIntent: (PodcastDetailIntent) -> Unit) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.query,
            onValueChange = { onIntent(PodcastDetailIntent.SetQuery(it)) },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            placeholder = { Text("Search episodes") },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EpisodeFilter.entries.forEach { filter ->
                FilterChip(
                    selected = state.filter == filter,
                    onClick = { onIntent(PodcastDetailIntent.SetFilter(filter)) },
                    label = { Text(filter.name.lowercase().replaceFirstChar { it.uppercase() }) },
                )
            }
        }
    }
}

/** A few lines of the podcast description as plain text: feeds write it as HTML, a preview should not show the tags. */
@Composable
internal fun DescriptionPreview(html: String, modifier: Modifier = Modifier) {
    val text = remember(html) { htmlToPlainText(html) }
    Text(
        text,
        modifier.testTag(TestTags.PODCAST_DESCRIPTION),
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.bodyMedium,
    )
}
