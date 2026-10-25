// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.sonora.ui.components.ArtworkImage
import org.sonora.ui.components.ScreenHeading
import org.sonora.ui.components.SonoraLogo

@Composable
fun LibraryScreen(
    onOpenPodcast: (Long) -> Unit,
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
    viewModel: SubscriptionsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.events.collect {
            snackbar.showSnackbar(
                when (it) {
                    SubscriptionsEvent.SyncStarted -> "Syncing your subscriptions…"
                    is SubscriptionsEvent.Message -> it.text
                },
            )
        }
    }

    LibraryContent(state, viewModel::onIntent, onOpenPodcast, modifier)
}

@Composable
internal fun LibraryContent(
    state: SubscriptionsUiState,
    onIntent: (SubscriptionsIntent) -> Unit,
    onOpenPodcast: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    state.confirmingUnsubscribe?.let { podcast ->
        AlertDialog(
            onDismissRequest = { onIntent(SubscriptionsIntent.DismissUnsubscribe) },
            title = { Text("Unsubscribe from ${podcast.title}?") },
            text = { Text("It disappears from your library. You can subscribe again from Discover.") },
            confirmButton = { TextButton(onClick = { onIntent(SubscriptionsIntent.ConfirmUnsubscribe) }) { Text("Unsubscribe") } },
            dismissButton = { TextButton(onClick = { onIntent(SubscriptionsIntent.DismissUnsubscribe) }) { Text("Cancel") } },
        )
    }

    Column(modifier.fillMaxSize()) {
        LibraryHeader(state, onIntent)
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { onIntent(SubscriptionsIntent.Refresh) },
            modifier = Modifier.weight(1f),
        ) {
            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) { LoadingIndicator() }
                state.podcasts.isEmpty() -> EmptyLibrary(state.unplayedOnly, state.query.trim())
                else -> AnimatedContent(targetState = state.layout, label = "layout") { layout ->
                    when (layout) {
                        LibraryLayout.GRID -> PodcastGrid(state.podcasts, onOpenPodcast, onIntent)
                        LibraryLayout.LIST -> PodcastList(state.podcasts, onOpenPodcast, onIntent)
                    }
                }
            }
        }
    }
}

/** Search, sort and filter, and layout are all icons, so the header costs one slim row. */
@Composable
private fun LibraryHeader(state: SubscriptionsUiState, onIntent: (SubscriptionsIntent) -> Unit) {
    ScreenHeading("Library")
    var searching by rememberSaveable { mutableStateOf(state.query.isNotBlank()) }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        SearchSlot(
            searching = searching,
            query = state.query,
            onQueryChange = { onIntent(SubscriptionsIntent.SetQuery(it)) },
            onClose = {
                onIntent(SubscriptionsIntent.SetQuery(""))
                searching = false
            },
            modifier = Modifier.weight(1f),
        )
        if (state.isRefreshing) LoadingIndicator(Modifier.size(40.dp))
        AnimatedVisibility(visible = !searching, enter = fadeIn(), exit = fadeOut()) {
            IconButton(onClick = { searching = true }) { Icon(Icons.Rounded.Search, contentDescription = "Search library") }
        }
        SortAndFilterButton(state, onIntent)
        IconToggleButton(
            checked = state.layout == LibraryLayout.LIST,
            onCheckedChange = { onIntent(SubscriptionsIntent.SetLayout(if (it) LibraryLayout.LIST else LibraryLayout.GRID)) },
        ) {
            Icon(
                if (state.layout == LibraryLayout.GRID) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView,
                contentDescription = if (state.layout == LibraryLayout.GRID) "Show as list" else "Show as grid",
            )
        }
    }
}

/** Its own function so no enclosing row's scope leaks into [AnimatedVisibility]. */
@Composable
private fun SearchSlot(
    searching: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        AnimatedVisibility(
            visible = searching,
            enter = expandHorizontally(expandFrom = Alignment.End) + fadeIn(),
            exit = shrinkHorizontally(shrinkTowards = Alignment.End) + fadeOut(),
        ) {
            LibrarySearchField(query, onQueryChange, onClose)
        }
    }
}

@Composable
private fun LibrarySearchField(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { if (query.isEmpty()) focus.requestFocus() }
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("Search library") },
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = {
            IconButton(onClick = { focusManager.clearFocus(); onClose() }) { Icon(Icons.Rounded.Close, contentDescription = "Close search") }
        },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
}

@Composable
private fun SortAndFilterButton(state: SubscriptionsUiState, onIntent: (SubscriptionsIntent) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            BadgedBox(badge = { if (state.unplayedOnly) Badge() }) {
                Icon(Icons.Rounded.Tune, contentDescription = "Sort and filter")
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                "Sort by",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            LibrarySort.entries.forEach { sort ->
                ChoiceItem(
                    label = when (sort) { LibrarySort.TITLE -> "A–Z"; LibrarySort.RECENT -> "Recent"; LibrarySort.UNPLAYED -> "Unplayed" },
                    selected = state.sort == sort,
                ) {
                    onIntent(SubscriptionsIntent.SetSort(sort))
                    open = false
                }
            }
            HorizontalDivider()
            ChoiceItem(label = "Has new", selected = state.unplayedOnly) {
                onIntent(SubscriptionsIntent.SetUnplayedOnly(!state.unplayedOnly))
                open = false
            }
        }
    }
}

/** A menu row that shows a check when it is the current choice and says so to TalkBack. */
@Composable
private fun ChoiceItem(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        leadingIcon = { if (selected) Icon(Icons.Rounded.Check, null) else Spacer(Modifier.size(24.dp)) },
        modifier = Modifier.semantics { this.selected = selected },
    )
}

@Composable
private fun EmptyLibrary(unplayedOnly: Boolean, query: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
            if (!unplayedOnly && query.isEmpty()) SonoraLogo(Modifier.fillMaxWidth(0.6f))
            Text(
                when {
                    query.isNotEmpty() -> "No shows match “$query”."
                    unplayedOnly -> "Nothing new right now."
                    else -> "No subscriptions yet. Find shows in Discover, or import an OPML file there."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The long-press menu for one show. [onDismiss] closes it before any action is reported. */
@Composable
private fun PodcastActionsMenu(
    podcast: PodcastItem,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onIntent: (SubscriptionsIntent) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Mark all played") },
            enabled = podcast.unplayedCount > 0,
            onClick = { onDismiss(); onIntent(SubscriptionsIntent.MarkAllPlayed(podcast.id)) },
        )
        DropdownMenuItem(
            text = { Text(if (podcast.autoDownload) "Turn off auto-download" else "Turn on auto-download") },
            onClick = { onDismiss(); onIntent(SubscriptionsIntent.ToggleAutoDownload(podcast.id)) },
        )
        DropdownMenuItem(
            text = { Text("Unsubscribe") },
            onClick = { onDismiss(); onIntent(SubscriptionsIntent.RequestUnsubscribe(podcast.id)) },
        )
    }
}

private fun unplayedLabel(count: Int) = if (count > 99) "99+" else count.toString()

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PodcastGrid(podcasts: List<PodcastItem>, onOpen: (Long) -> Unit, onIntent: (SubscriptionsIntent) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(podcasts, key = { it.id }) { podcast ->
            var menu by remember { mutableStateOf(false) }
            Box {
                Column(
                    Modifier.combinedClickable(
                        onClick = { onOpen(podcast.id) },
                        onLongClickLabel = "Show actions",
                        onLongClick = { menu = true },
                    ),
                ) {
                    Box {
                        ArtworkImage(podcast.imageUrl, podcast.title, Modifier.fillMaxWidth().aspectRatio(1f))
                        if (podcast.unplayedCount > 0) {
                            Badge(Modifier.align(Alignment.TopEnd).padding(8.dp)) { Text(unplayedLabel(podcast.unplayedCount)) }
                        }
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
                PodcastActionsMenu(podcast, menu, { menu = false }, onIntent)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PodcastList(podcasts: List<PodcastItem>, onOpen: (Long) -> Unit, onIntent: (SubscriptionsIntent) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(podcasts, key = { it.id }) { podcast ->
            var menu by remember { mutableStateOf(false) }
            Box {
                ListItem(
                    modifier = Modifier.combinedClickable(
                        onClick = { onOpen(podcast.id) },
                        onLongClickLabel = "Show actions",
                        onLongClick = { menu = true },
                    ),
                    leadingContent = { ArtworkImage(podcast.imageUrl, podcast.title, Modifier.size(64.dp)) },
                    content = { Text(podcast.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMediumEmphasized) },
                    supportingContent = podcast.author?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                    trailingContent = { if (podcast.unplayedCount > 0) Badge { Text(unplayedLabel(podcast.unplayedCount)) } },
                )
                PodcastActionsMenu(podcast, menu, { menu = false }, onIntent)
            }
        }
    }
}
