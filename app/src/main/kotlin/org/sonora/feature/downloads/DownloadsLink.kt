// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
    val evicted by viewModel.evictedUnplayed.collectAsStateWithLifecycle()
    DownloadsLinkContent(usage, evicted, onClick, modifier)
}

@Composable
fun DownloadsLinkContent(usage: StorageUsage, evictedUnplayed: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ListItem(
        modifier = modifier.testTag(TestTags.DOWNLOADS_LINK).clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        content = { Text("Manage downloads") },
        supportingContent = { Text(formatUsageSummary(usage)) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (evictedUnplayed > 0) {
                    Badge(
                        modifier = Modifier.testTag(TestTags.DOWNLOADS_LINK_BADGE).semantics {
                            contentDescription = "$evictedUnplayed unplayed episode${if (evictedUnplayed == 1) "" else "s"} removed"
                        },
                    ) { Text(if (evictedUnplayed > 99) "99+" else "$evictedUnplayed") }
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
            }
        },
    )
}
