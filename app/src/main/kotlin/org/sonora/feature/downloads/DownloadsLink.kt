// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.downloads

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.sonora.core.testing.TestTags
import org.sonora.ui.formatUsageSummary

/** "Manage downloads" with the current usage underneath; the way into [DownloadsScreen]. */
@Composable
fun DownloadsLink(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DownloadsSummaryViewModel = hiltViewModel(),
) {
    val usage by viewModel.usage.collectAsStateWithLifecycle()
    DownloadsLinkContent(usage, onClick, modifier)
}

@Composable
fun DownloadsLinkContent(usage: StorageUsage, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ListItem(
        modifier = modifier.testTag(TestTags.DOWNLOADS_LINK).clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        content = { Text("Manage downloads") },
        supportingContent = { Text(formatUsageSummary(usage)) },
        trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) },
    )
}
