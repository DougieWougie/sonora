// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.rememberSliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import org.sonora.ui.formatRemaining
import org.sonora.ui.formatTimestamp
import org.sonora.ui.theme.tabular

/**
 * A tactile seek bar: the played part is a wave that rises while audio is playing and flattens when
 * paused, with the buffered range as a thin line beneath. A tooltip shows the target time while dragging.
 */
@Composable
fun ExpressiveScrubber(
    positionMs: Long,
    bufferedMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val enabled = durationMs > 0
    val playedFraction = if (enabled) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val fraction = if (dragging) dragFraction else playedFraction
    val bufferedFraction = if (enabled) (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val amplitude by animateFloatAsState(
        targetValue = if (isPlaying && !dragging) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "waveAmplitude",
    )
    val shownPosition = if (dragging) (dragFraction * durationMs).toLong() else positionMs

    val sliderState = rememberSliderState(value = playedFraction)
    LaunchedEffect(playedFraction, dragging) { if (!dragging) sliderState.value = playedFraction }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.BottomCenter) {
            androidx.compose.animation.AnimatedVisibility(
                visible = dragging,
                enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) + scaleIn(MaterialTheme.motionScheme.fastSpatialSpec()),
                exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()) + scaleOut(MaterialTheme.motionScheme.fastSpatialSpec()),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.inverseSurface) {
                    Text(
                        text = formatTimestamp(shownPosition),
                        style = MaterialTheme.typography.labelLargeEmphasized.tabular(),
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            LinearProgressIndicator(
                progress = { bufferedFraction },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(bottom = 4.dp).height(2.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
            Slider(
                state = sliderState,
                onValueChange = { dragging = true; dragFraction = it; sliderState.value = it },
                onValueChangeFinished = {
                    dragging = false
                    onSeek((dragFraction * durationMs).toLong())
                },
                enabled = enabled,
                modifier = Modifier.semantics {
                    contentDescription = "Seek"
                    stateDescription = "${formatTimestamp(positionMs)} of ${formatTimestamp(durationMs)}"
                },
                thumb = {
                    Box(
                        Modifier
                            .width(if (dragging) 4.dp else 6.dp)
                            .height(if (dragging) 36.dp else 28.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)),
                    )
                },
                track = {
                    Box(Modifier.fillMaxWidth().height(32.dp), contentAlignment = Alignment.Center) {
                        LinearWavyProgressIndicator(
                            progress = { fraction },
                            amplitude = { amplitude },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTimestamp(shownPosition), style = MaterialTheme.typography.labelLargeEmphasized.tabular())
            Text(
                formatRemaining(shownPosition, durationMs),
                style = MaterialTheme.typography.labelLargeEmphasized.tabular(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
