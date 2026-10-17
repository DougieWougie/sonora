// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.discover

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.sonora.ui.components.ArtworkImage

@Composable
fun DiscoverScreen(
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
    viewModel: DiscoverViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val stream = uri?.let { context.contentResolver.openInputStream(it) } ?: return@rememberLauncherForActivityResult
        viewModel.onIntent(DiscoverIntent.ImportOpml(stream))
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x-opml")) { uri ->
        val stream = uri?.let { context.contentResolver.openOutputStream(it) } ?: return@rememberLauncherForActivityResult
        viewModel.onIntent(DiscoverIntent.ExportOpml(stream))
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { if (it is DiscoverEvent.Message) snackbar.showSnackbar(it.text) }
    }
    LaunchedEffect(state.opml) {
        val text = when (val opml = state.opml) {
            is OpmlStatus.Imported -> buildString {
                append("Imported ${opml.result.imported} podcast(s)")
                if (opml.result.alreadySubscribed > 0) append(", ${opml.result.alreadySubscribed} already subscribed")
                if (opml.result.rejected > 0) append(", ${opml.result.rejected} skipped (not HTTPS)")
            }
            is OpmlStatus.Exported -> "Exported ${opml.count} subscription(s)"
            is OpmlStatus.Failed -> "OPML failed: ${opml.message}"
            OpmlStatus.Idle, OpmlStatus.Working -> null
        }
        if (text != null) {
            snackbar.showSnackbar(text)
            viewModel.onIntent(DiscoverIntent.DismissOpmlStatus)
        }
    }

    LazyColumn(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { Text("Discover", style = MaterialTheme.typography.headlineLargeEmphasized, modifier = Modifier.padding(16.dp)) }
        item {
            OutlinedTextField(
                value = state.query,
                onValueChange = { viewModel.onIntent(DiscoverIntent.QueryChanged(it)) },
                placeholder = { Text("Search podcasts") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
        item {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DirectorySource.entries.forEach { source ->
                    val selected = state.directory == source
                    FilterChip(
                        selected = selected,
                        onClick = { viewModel.onIntent(DiscoverIntent.SelectDirectory(source)) },
                        label = { Text(if (source == DirectorySource.APPLE) "Apple Podcasts" else "PodcastIndex") },
                        leadingIcon = {
                            AnimatedVisibility(selected, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                                Icon(Icons.Rounded.Check, null, Modifier.size(18.dp))
                            }
                        },
                    )
                }
                AssistChip(onClick = { viewModel.onIntent(DiscoverIntent.ShowAddUrl) }, label = { Text("Add by URL") }, leadingIcon = { Icon(Icons.Rounded.Link, null, Modifier.size(18.dp)) })
                AssistChip(onClick = { importer.launch(arrayOf("text/x-opml", "text/xml", "application/xml", "*/*")) }, label = { Text("Import OPML") }, leadingIcon = { Icon(Icons.Rounded.FileDownload, null, Modifier.size(18.dp)) })
                AssistChip(onClick = { exporter.launch("sonora-subscriptions.opml") }, label = { Text("Export OPML") }, leadingIcon = { Icon(Icons.Rounded.FileUpload, null, Modifier.size(18.dp)) })
            }
        }
        if (state.isSearching || state.opml == OpmlStatus.Working) {
            item { Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { LoadingIndicator() } }
        }
        state.error?.let { error ->
            item { Text(error, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
        }
        items(state.results, key = { it.podcast.feedUrl }) { result ->
            ListItem(
                leadingContent = { ArtworkImage(result.podcast.artworkUrl, result.podcast.title, Modifier.size(64.dp)) },
                headlineContent = { Text(result.podcast.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMediumEmphasized) },
                supportingContent = result.podcast.author?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                trailingContent = {
                    FilledTonalButton(
                        onClick = { viewModel.onIntent(DiscoverIntent.Subscribe(result.podcast)) },
                        enabled = !result.subscribed,
                    ) {
                        Icon(if (result.subscribed) Icons.Rounded.Check else Icons.Rounded.Add, null, Modifier.size(18.dp))
                        Text(if (result.subscribed) "Added" else "Subscribe", Modifier.padding(start = 6.dp))
                    }
                },
            )
        }
        if (state.query.isBlank() && state.results.isEmpty()) {
            item { Text("Search a directory, paste a feed address, or import your subscriptions.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }

    state.addUrl?.let { dialog ->
        AlertDialog(
            onDismissRequest = { viewModel.onIntent(DiscoverIntent.DismissAddUrl) },
            title = { Text("Add by feed URL") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = dialog.url,
                        onValueChange = { viewModel.onIntent(DiscoverIntent.AddUrlChanged(it)) },
                        label = { Text("https://example.com/feed.xml") },
                        singleLine = true,
                        isError = dialog.error != null,
                        supportingText = dialog.error?.let { { Text(it) } },
                    )
                    if (dialog.isSubmitting) LoadingIndicator(Modifier.size(40.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.onIntent(DiscoverIntent.SubmitUrl) }, enabled = !dialog.isSubmitting) { Text("Subscribe") }
            },
            dismissButton = { TextButton(onClick = { viewModel.onIntent(DiscoverIntent.DismissAddUrl) }) { Text("Cancel") } },
        )
    }
}
