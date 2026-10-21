// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.sonora.core.testing.TestTags
import org.sonora.ui.components.ArtworkImage
import org.sonora.ui.components.ExpressiveScrubber
import org.sonora.ui.components.MorphingPlayPauseButton
import org.sonora.ui.components.QueueDragHandle
import org.sonora.ui.components.rememberReorderState
import org.sonora.ui.formatTimestamp
import org.sonora.ui.rememberHtml
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
        modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth().testTag(TestTags.MINI_PLAYER_BAR).clickable(onClick = onExpand),
    ) {
        Column {
            Row(Modifier.padding(start = 16.dp, top = 12.dp, end = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ArtworkImage(
                    episode.artworkUrl, null,
                    Modifier.size(64.dp).sharedElement(rememberSharedContentState(KEY_ARTWORK), visibilityScope, boundsTransform = transform),
                    shape = RoundedCornerShape(16.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        episode.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMediumEmphasized,
                        modifier = Modifier.sharedBounds(rememberSharedContentState(KEY_TITLE), visibilityScope, boundsTransform = transform),
                    )
                    episode.podcastTitle?.let {
                        Text(
                            it,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                        )
                    }
                }
                MorphingPlayPauseButton(
                    isPlaying = state.isPlaying,
                    onClick = { onIntent(PlayerIntent.TogglePlayPause) },
                    size = 56.dp,
                    modifier = Modifier.testTag(TestTags.PLAY_PAUSE_BUTTON).sharedElement(rememberSharedContentState(KEY_PLAY), visibilityScope, boundsTransform = transform),
                )
            }
            LinearProgressIndicator(
                progress = { if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 10.dp).height(4.dp),
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
    onShare: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val episode = state.episode ?: return
    val transform = boundsTransform()
    var showQueue by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize().testTag(TestTags.FULL_PLAYER_SHEET),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Now playing", style = MaterialTheme.typography.labelLargeEmphasized) },
                navigationIcon = {
                    IconButton(onClick = onCollapse, modifier = Modifier.testTag(TestTags.PLAYER_COLLAPSE_BUTTON)) {
                        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Minimise player")
                    }
                },
                actions = {
                    IconButton(onClick = onShare, modifier = Modifier.testTag(TestTags.PLAYER_SHARE_BUTTON)) {
                        Icon(Icons.Outlined.Share, contentDescription = "Share episode")
                    }
                },
            )
        },
        bottomBar = { PlayerDock(state, visibilityScope, transform, onIntent) },
    ) { innerPadding ->
        Column(
            Modifier.testTag(TestTags.PLAYER_SCROLL_BODY).fillMaxSize().verticalScroll(rememberScrollState()).padding(innerPadding).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                shadowElevation = 4.dp,
                modifier = Modifier.align(Alignment.CenterHorizontally).widthIn(max = 360.dp).fillMaxWidth().aspectRatio(1f),
            ) {
                ArtworkImage(
                    episode.artworkUrl, episode.title,
                    Modifier.fillMaxSize().sharedElement(rememberSharedContentState(KEY_ARTWORK), visibilityScope, boundsTransform = transform),
                    shape = RoundedCornerShape(28.dp),
                )
            }
            Column(Modifier.fillMaxWidth()) {
                Text(
                    episode.title,
                    style = MaterialTheme.typography.headlineMediumEmphasized,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().sharedBounds(rememberSharedContentState(KEY_TITLE), visibilityScope, boundsTransform = transform),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                episode.podcastTitle?.let {
                    Text(it, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
                }
            }
            SecondaryControls(state, onIntent, onOpenQueue = { showQueue = true })
            episode.showNotes?.let { ShowNotes(it) }
        }
    }
    if (showQueue) QueueSheet(state, onIntent, onDismiss = { showQueue = false })
}

/** Scrubber, times and transport controls, pinned under the scrolling body. */
@Composable
private fun SharedTransitionScope.PlayerDock(
    state: PlayerUiState,
    visibilityScope: AnimatedVisibilityScope,
    transform: androidx.compose.animation.BoundsTransform,
    onIntent: (PlayerIntent) -> Unit,
) {
    Surface(
        tonalElevation = 3.dp,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        modifier = Modifier.fillMaxWidth().testTag(TestTags.PLAYER_DOCK),
    ) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp).padding(top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ExpressiveScrubber(
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                isPlaying = state.isPlaying,
                onSeek = { onIntent(PlayerIntent.SeekTo(it)) },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onIntent(PlayerIntent.Previous) }, modifier = Modifier.size(48.dp).testTag(TestTags.PREVIOUS_BUTTON)) {
                    Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous episode", Modifier.size(28.dp))
                }
                FilledTonalIconButton(onClick = { onIntent(PlayerIntent.Rewind) }, modifier = Modifier.size(56.dp).testTag(TestTags.SKIP_BACK_BUTTON)) {
                    Icon(Icons.Rounded.Replay10, contentDescription = "Back 10 seconds", Modifier.size(32.dp))
                }
                val description = if (state.isPlaying) "Pause" else "Play"
                FilledIconButton(
                    onClick = { onIntent(PlayerIntent.TogglePlayPause) },
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.size(width = 72.dp, height = 72.dp).testTag(TestTags.PLAY_PAUSE_BUTTON)
                        .sharedElement(rememberSharedContentState(KEY_PLAY), visibilityScope, boundsTransform = transform)
                        .semantics { contentDescription = description },
                ) {
                    Crossfade(targetState = state.isPlaying, animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(), label = "playPauseIcon") { playing ->
                        Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = null, Modifier.size(40.dp))
                    }
                }
                FilledTonalIconButton(onClick = { onIntent(PlayerIntent.SkipForward) }, modifier = Modifier.size(56.dp).testTag(TestTags.SKIP_FORWARD_BUTTON)) {
                    Icon(Icons.Rounded.Forward30, contentDescription = "Forward 30 seconds", Modifier.size(32.dp))
                }
                IconButton(onClick = { onIntent(PlayerIntent.Next) }, enabled = state.hasNext, modifier = Modifier.size(48.dp).testTag(TestTags.NEXT_BUTTON)) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "Next episode", Modifier.size(28.dp))
                }
            }
        }
    }
}

/** The episode's show notes, with their links and emphasis. Nothing is drawn when the feed has none worth showing. */
@Composable
private fun ShowNotes(html: String) {
    val notes = rememberHtml(html)
    if (notes.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Show notes", style = MaterialTheme.typography.titleMediumEmphasized)
        Text(notes, Modifier.testTag(TestTags.SHOW_NOTES), style = MaterialTheme.typography.bodyLarge)
    }
}

/** Speed, silence trimming, sleep timer and the queue, each a chip with a visible label. */
@Composable
private fun SecondaryControls(state: PlayerUiState, onIntent: (PlayerIntent) -> Unit, onOpenQueue: () -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().testTag(TestTags.PLAYER_TOOLBAR),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SpeedPill(state.speed, onIntent)
        SkipSilenceChip(state.skipSilence) { onIntent(PlayerIntent.ToggleSkipSilence) }
        SleepTimerButton(state.sleepTimerRemainingMs, onIntent)
        AssistChip(
            onClick = onOpenQueue,
            modifier = Modifier.testTag(TestTags.QUEUE_CHIP),
            label = { Text("Queue") },
            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null, Modifier.size(18.dp)) },
        )
    }
}

@Composable
private fun SpeedPill(speed: Float, onIntent: (PlayerIntent) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            modifier = Modifier.testTag(TestTags.SPEED_CONTROL_BUTTON),
            label = { Text("%.1f×".format(speed), style = MaterialTheme.typography.labelLargeEmphasized.tabular()) },
            leadingIcon = { Icon(Icons.Rounded.Speed, contentDescription = null, Modifier.size(18.dp)) },
        )
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
        label = { Text(if (enabled) "No gap" else "Gap") },
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
        FilterChip(
            selected = remainingMs != null,
            onClick = { open = true },
            modifier = Modifier.testTag(TestTags.SLEEP_TIMER_BUTTON),
            label = { Text(if (remainingMs == null) "Sleep" else formatTimestamp(remainingMs, roundUp = true), style = MaterialTheme.typography.labelLarge.tabular()) },
            leadingIcon = { Icon(Icons.Outlined.Bedtime, contentDescription = null, Modifier.size(18.dp)) },
        )
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
        val listState = rememberLazyListState()
        val reorder = rememberReorderState(listState, { state.upNext.map { it.id } }, { from, to -> onIntent(PlayerIntent.MoveInQueue(state.upNext[from].id, to)) })
        LazyColumn(state = listState) {
            itemsIndexed(state.upNext, key = { _, it -> it.id }) { index, next ->
                ListItem(
                    modifier = with(reorder) { Modifier.reorderable(next.id).let { if (next.id == draggedKey) it else it.animateItem() } }
                        .clickable { onIntent(PlayerIntent.PlayQueued(next.id)); onDismiss() },
                    leadingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            QueueDragHandle(
                                title = next.title,
                                key = next.id,
                                state = reorder,
                                canMoveUp = index > 0,
                                canMoveDown = index < state.upNext.lastIndex,
                                onMoveUp = { onIntent(PlayerIntent.MoveInQueue(next.id, index - 1)) },
                                onMoveDown = { onIntent(PlayerIntent.MoveInQueue(next.id, index + 1)) },
                            )
                            ArtworkImage(next.artworkUrl, null, Modifier.size(48.dp), RoundedCornerShape(12.dp))
                        }
                    },
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

/** A plain-text share of what is playing; feeds carry no stable episode URL here, so the title and show are shared. */
fun shareIntent(episode: PlayerEpisode): android.content.Intent =
    android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_SUBJECT, episode.title)
        putExtra(android.content.Intent.EXTRA_TEXT, listOfNotNull(episode.title, episode.podcastTitle).joinToString(" — "))
    }
