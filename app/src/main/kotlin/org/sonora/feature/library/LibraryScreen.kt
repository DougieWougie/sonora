// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.sonora.ui.components.ArtworkImage

@Composable
fun LibraryScreen(
    onOpenPodcast: (Long) -> Unit,
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
    viewModel: SubscriptionsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.events.collect { if (it == SubscriptionsEvent.SyncStarted) snackbar.showSnackbar("Syncing your subscriptions…") }
    }

    Column(modifier.fillMaxSize()) {
        LibraryHeader(state, viewModel::onIntent)
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { viewModel.onIntent(SubscriptionsIntent.Refresh) },
            modifier = Modifier.weight(1f),
        ) {
            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) { LoadingIndicator() }
                state.podcasts.isEmpty() -> EmptyLibrary(state.unplayedOnly)
                else -> AnimatedContent(targetState = state.layout, label = "layout") { layout ->
                    when (layout) {
                        LibraryLayout.GRID -> PodcastGrid(state.podcasts, onOpenPodcast)
                        LibraryLayout.LIST -> PodcastList(state.podcasts, onOpenPodcast)
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryHeader(state: SubscriptionsUiState, onIntent: (SubscriptionsIntent) -> Unit) {
    Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp).animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Library", style = MaterialTheme.typography.headlineLargeEmphasized, modifier = Modifier.weight(1f))
            if (state.isRefreshing) LoadingIndicator(Modifier.size(40.dp))
            IconToggleButton(
                checked = state.layout == LibraryLayout.LIST,
                onCheckedChange = { onIntent(SubscriptionsIntent.SetLayout(if (it) LibraryLayout.LIST else LibraryLayout.GRID)) },
            ) {
                Icon(
                    if (state.layout == LibraryLayout.GRID) Icons.Rounded.ViewList else Icons.Rounded.GridView,
                    contentDescription = if (state.layout == LibraryLayout.GRID) "Show as list" else "Show as grid",
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            LibrarySort.entries.forEach { sort ->
                FilterChip(
                    selected = state.sort == sort,
                    onClick = { onIntent(SubscriptionsIntent.SetSort(sort)) },
                    label = { Text(when (sort) { LibrarySort.TITLE -> "A–Z"; LibrarySort.RECENT -> "Recent"; LibrarySort.UNPLAYED -> "Unplayed" }) },
                )
            }
            FilterChip(
                selected = state.unplayedOnly,
                onClick = { onIntent(SubscriptionsIntent.SetUnplayedOnly(!state.unplayedOnly)) },
                label = { Text("Has new") },
            )
        }
    }
}

@Composable
private fun EmptyLibrary(filtered: Boolean) {
    Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
        Text(
            if (filtered) "Nothing new right now." else "No subscriptions yet. Find shows in Discover, or import an OPML file there.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PodcastGrid(podcasts: List<PodcastItem>, onOpen: (Long) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(podcasts, key = { it.id }) { podcast ->
            Column(Modifier.clickable { onOpen(podcast.id) }) {
                BadgedBox(badge = { if (podcast.unplayedCount > 0) Badge { Text(podcast.unplayedCount.toString()) } }) {
                    ArtworkImage(podcast.imageUrl, podcast.title, Modifier.fillMaxWidth().aspectRatio(1f))
                }
                Text(
                    podcast.title,
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp),
                )
                podcast.author?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun PodcastList(podcasts: List<PodcastItem>, onOpen: (Long) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(podcasts, key = { it.id }) { podcast ->
            ListItem(
                modifier = Modifier.clickable { onOpen(podcast.id) },
                leadingContent = { ArtworkImage(podcast.imageUrl, podcast.title, Modifier.size(64.dp)) },
                headlineContent = { Text(podcast.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMediumEmphasized) },
                supportingContent = podcast.author?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                trailingContent = { if (podcast.unplayedCount > 0) Badge { Text(podcast.unplayedCount.toString()) } },
            )
        }
    }
}
