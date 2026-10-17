// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.sonora.core.testing.TestTags
import org.sonora.ui.components.ArtworkImage
import org.sonora.ui.components.ExpressiveScrubber
import org.sonora.ui.components.MorphingPlayPauseButton
import org.sonora.ui.formatTimestamp
import org.sonora.ui.theme.tabular

private const val KEY_ARTWORK = "player-artwork"
private const val KEY_TITLE = "player-title"
private const val KEY_PLAY = "player-play"

@Composable
private fun boundsTransform(): androidx.compose.animation.BoundsTransform {
    val spec: FiniteAnimationSpec<Rect> = MaterialTheme.motionScheme.defaultSpatialSpec()
    return androidx.compose.animation.BoundsTransform { _, _ -> spec }
}

/** Docked player: artwork, title and play/pause morph into [FullPlayer] through shared elements. */
@Composable
fun SharedTransitionScope.MiniPlayer(
    state: PlayerUiState,
    visibilityScope: AnimatedVisibilityScope,
    onIntent: (PlayerIntent) -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val episode = state.episode ?: return
    val transform = boundsTransform()
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 3.dp,
        modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp).fillMaxWidth().testTag(TestTags.MINI_PLAYER_BAR).clickable(onClick = onExpand),
    ) {
        Column {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ArtworkImage(
                    episode.artworkUrl, null,
                    Modifier.size(48.dp).sharedElement(rememberSharedContentState(KEY_ARTWORK), visibilityScope, boundsTransform = transform),
                    shape = RoundedCornerShape(12.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        episode.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmallEmphasized,
                        modifier = Modifier.sharedBounds(rememberSharedContentState(KEY_TITLE), visibilityScope, boundsTransform = transform),
                    )
                    episode.podcastTitle?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
                }
                MorphingPlayPauseButton(
                    isPlaying = state.isPlaying,
                    onClick = { onIntent(PlayerIntent.TogglePlayPause) },
                    size = 48.dp,
                    modifier = Modifier.testTag(TestTags.PLAY_PAUSE_BUTTON).sharedElement(rememberSharedContentState(KEY_PLAY), visibilityScope, boundsTransform = transform),
                )
            }
            LinearProgressIndicator(
                progress = { if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 6.dp),
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
    }
}

@Composable
fun SharedTransitionScope.FullPlayer(
    state: PlayerUiState,
    visibilityScope: AnimatedVisibilityScope,
    onIntent: (PlayerIntent) -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val episode = state.episode ?: return
    val transform = boundsTransform()
    var showQueue by remember { mutableStateOf(false) }

    Surface(modifier.fillMaxSize().testTag(TestTags.FULL_PLAYER_SHEET), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCollapse) { Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Minimise player") }
                Text("Now playing", Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLargeEmphasized)
                Box(Modifier.size(48.dp))
            }
            ArtworkImage(
                episode.artworkUrl, episode.title,
                Modifier.widthIn(max = 360.dp).fillMaxWidth().aspectRatio(1f)
                    .sharedElement(rememberSharedContentState(KEY_ARTWORK), visibilityScope, boundsTransform = transform),
                shape = RoundedCornerShape(topStart = 56.dp, topEnd = 16.dp, bottomEnd = 56.dp, bottomStart = 16.dp),
            )
            Column(Modifier.fillMaxWidth()) {
                Text(
                    episode.title,
                    style = MaterialTheme.typography.headlineSmallEmphasized,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.sharedBounds(rememberSharedContentState(KEY_TITLE), visibilityScope, boundsTransform = transform),
                )
                episode.podcastTitle?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            ExpressiveScrubber(
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                isPlaying = state.isPlaying,
                onSeek = { onIntent(PlayerIntent.SeekTo(it)) },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onIntent(PlayerIntent.Rewind) }, modifier = Modifier.size(56.dp).testTag(TestTags.SKIP_BACK_BUTTON)) {
                    Icon(Icons.Rounded.Replay, contentDescription = "Back 15 seconds", Modifier.size(32.dp))
                }
                MorphingPlayPauseButton(
                    isPlaying = state.isPlaying,
                    onClick = { onIntent(PlayerIntent.TogglePlayPause) },
                    size = 96.dp,
                    modifier = Modifier.testTag(TestTags.PLAY_PAUSE_BUTTON).sharedElement(rememberSharedContentState(KEY_PLAY), visibilityScope, boundsTransform = transform),
                )
                IconButton(onClick = { onIntent(PlayerIntent.SkipForward) }, modifier = Modifier.size(56.dp).testTag(TestTags.SKIP_FORWARD_BUTTON)) {
                    Icon(Icons.Rounded.Forward30, contentDescription = "Forward 30 seconds", Modifier.size(32.dp))
                }
            }
            SecondaryControls(state, onIntent, onOpenQueue = { showQueue = true })
        }
    }
    if (showQueue) QueueSheet(state, onIntent, onDismiss = { showQueue = false })
}

/** Speed, silence trimming, sleep timer and the queue, in one floating toolbar. */
@Composable
private fun SecondaryControls(state: PlayerUiState, onIntent: (PlayerIntent) -> Unit, onOpenQueue: () -> Unit) {
    HorizontalFloatingToolbar(
        expanded = true,
        modifier = Modifier.testTag(TestTags.PLAYER_TOOLBAR),
        floatingActionButton = {
            FloatingToolbarDefaults.StandardFloatingActionButton(onClick = onOpenQueue) {
                Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = "Up next (${state.upNext.size})")
            }
        },
        colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
    ) {
        SpeedPill(state.speed, onIntent)
        Spacer(Modifier.width(8.dp))
        SkipSilenceChip(state.skipSilence) { onIntent(PlayerIntent.ToggleSkipSilence) }
        SleepTimerButton(state.sleepTimerRemainingMs, onIntent)
    }
}

@Composable
private fun SpeedPill(speed: Float, onIntent: (PlayerIntent) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Button(onClick = { open = true }, shape = RoundedCornerShape(50), modifier = Modifier.testTag(TestTags.SPEED_CONTROL_BUTTON)) {
            Text("%.1f×".format(speed), style = MaterialTheme.typography.labelLargeEmphasized.tabular())
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Faster (+0.1×)") }, onClick = { onIntent(PlayerIntent.StepSpeed(+1)) })
            DropdownMenuItem(text = { Text("Slower (−0.1×)") }, onClick = { onIntent(PlayerIntent.StepSpeed(-1)) })
            listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f).forEach { preset ->
                DropdownMenuItem(
                    modifier = Modifier.testTag(TestTags.speedPreset(preset)),
                    text = { Text("%.2f×".format(preset).replace(Regex("0$"), "")) },
                    trailingIcon = { if (preset == speed) Icon(Icons.Rounded.Check, null) },
                    onClick = { open = false; onIntent(PlayerIntent.SetSpeed(preset)) },
                )
            }
        }
    }
}

@Composable
private fun SkipSilenceChip(enabled: Boolean, onToggle: () -> Unit) {
    FilterChip(
        selected = enabled,
        onClick = onToggle,
        modifier = Modifier.testTag(TestTags.SKIP_SILENCE_CHIP),
        label = { Text("Skip silence") },
        colors = FilterChipDefaults.filterChipColors(
            labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            iconColor = MaterialTheme.colorScheme.onPrimaryContainer,
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = enabled,
            borderColor = MaterialTheme.colorScheme.onPrimaryContainer,
            selectedBorderColor = MaterialTheme.colorScheme.primary,
        ),
        leadingIcon = {
            AnimatedContent(
                targetState = enabled,
                transitionSpec = { fadeIn(spring()) togetherWith fadeOut(spring()) },
                label = "silenceIcon",
            ) { on ->
                Icon(if (on) Icons.Rounded.Check else Icons.Rounded.GraphicEq, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        },
    )
}

private fun <T> spring() = androidx.compose.animation.core.spring<T>(stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow)

@Composable
private fun SleepTimerButton(remainingMs: Long?, onIntent: (PlayerIntent) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        if (remainingMs == null) {
            IconButton(onClick = { open = true }, modifier = Modifier.testTag(TestTags.SLEEP_TIMER_BUTTON)) { Icon(Icons.Rounded.Bedtime, contentDescription = "Sleep timer") }
        } else {
            TextButton(onClick = { open = true }, modifier = Modifier.testTag(TestTags.SLEEP_TIMER_BUTTON)) {
                Icon(Icons.Rounded.Bedtime, null, Modifier.size(18.dp))
                Text(formatTimestamp(remainingMs, roundUp = true), Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelLargeEmphasized.tabular())
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(5, 15, 30, 45, 60).forEach { minutes ->
                DropdownMenuItem(text = { Text("$minutes minutes") }, onClick = { open = false; onIntent(PlayerIntent.SetSleepTimer(minutes * 60_000L)) })
            }
            if (remainingMs != null) {
                DropdownMenuItem(text = { Text("Turn off") }, onClick = { open = false; onIntent(PlayerIntent.CancelSleepTimer) })
            }
        }
    }
}

@Composable
private fun QueueSheet(state: PlayerUiState, onIntent: (PlayerIntent) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Up next", style = MaterialTheme.typography.titleLargeEmphasized, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        if (state.upNext.isEmpty()) {
            Text("The queue is empty. Swipe an episode right to add it.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn {
            items(state.upNext, key = { it.id }) { next ->
                ListItem(
                    modifier = Modifier.clickable { onIntent(PlayerIntent.PlayQueued(next.id)); onDismiss() },
                    leadingContent = { ArtworkImage(next.artworkUrl, null, Modifier.size(48.dp), RoundedCornerShape(12.dp)) },
                    content = { Text(next.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = next.podcastTitle?.let { { Text(it, maxLines = 1) } },
                    trailingContent = {
                        IconButton(onClick = { onIntent(PlayerIntent.RemoveFromQueue(next.id)) }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Remove from queue")
                        }
                    },
                )
            }
        }
    }
}
