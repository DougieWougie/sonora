// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.sonora.core.media.PlaybackSource
import org.sonora.core.testing.TestTags

internal val PlaybackSource.label: String
    get() = when (this) {
        PlaybackSource.STREAMING -> "Streaming"
        PlaybackSource.OFFLINE -> "Offline"
    }

internal val PlaybackSource.icon: ImageVector
    get() = when (this) {
        PlaybackSource.STREAMING -> Icons.Rounded.CloudQueue
        PlaybackSource.OFFLINE -> Icons.Rounded.DownloadDone
    }

/**
 * A pill saying whether the audio is being streamed or played from the downloaded file. Streaming is
 * tonal-tertiary (it depends on the network); offline is secondary. The icon and label cross-morph
 * with spring motion when the source changes.
 */
@Composable
fun PlaybackSourceChip(source: PlaybackSource, modifier: Modifier = Modifier) {
    val streaming = source == PlaybackSource.STREAMING
    val enterEffects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val exitEffects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val spatial = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    Surface(
        shape = CircleShape,
        color = if (streaming) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (streaming) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier,
    ) {
        AnimatedContent(
            targetState = source,
            transitionSpec = {
                (fadeIn(enterEffects) + scaleIn(spatial, initialScale = 0.8f)) togetherWith
                    (fadeOut(exitEffects) + scaleOut(spatial, targetScale = 0.8f))
            },
            label = "playbackSource",
        ) { shown ->
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
            ) {
                Icon(shown.icon, contentDescription = null, Modifier.size(18.dp))
                Text(shown.label, Modifier.testTag(TestTags.SOURCE_CHIP), style = MaterialTheme.typography.labelLargeEmphasized)
            }
        }
    }
}

/** The mini player's quieter version of [PlaybackSourceChip]: just the icon. */
@Composable
fun PlaybackSourceIcon(source: PlaybackSource, modifier: Modifier = Modifier) {
    val spatial = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    AnimatedContent(
        targetState = source,
        transitionSpec = { scaleIn(spatial) togetherWith scaleOut(spatial) },
        label = "miniPlaybackSource",
        modifier = modifier,
    ) { shown ->
        Icon(
            shown.icon,
            contentDescription = shown.label,
            modifier = Modifier.size(16.dp).testTag(TestTags.MINI_SOURCE_ICON),
            tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
        )
    }
}
