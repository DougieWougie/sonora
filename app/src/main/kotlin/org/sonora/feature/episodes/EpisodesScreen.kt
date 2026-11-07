// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.episodes

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import org.sonora.feature.settings.SettingsStore
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.Serializable
import org.sonora.feature.downloads.DownloadsContent
import org.sonora.feature.downloads.DownloadsEvent
import org.sonora.feature.downloads.DownloadsViewModel
import org.sonora.feature.queue.NewEpisodesContent
import org.sonora.feature.queue.QueueContent
import org.sonora.feature.queue.QueueEvent
import org.sonora.feature.queue.QueueIntent
import org.sonora.feature.queue.QueueViewModel
import org.sonora.ui.components.EpisodeActionsUi
import org.sonora.ui.components.ScreenHeading

/** The views of "episodes I might listen to": what is lined up, what is new, and what is on the device. */
@Serializable
enum class EpisodeSegment(val label: String) {
    QUEUE("Queue"),
    NEW("New"),
    DOWNLOADED("Downloaded"),
}

/** Remembers which segment the user was on, so the tab opens where they left it. */
@HiltViewModel
class EpisodesViewModel @Inject constructor(private val settings: SettingsStore) : ViewModel() {
    val lastSegment: EpisodeSegment
        get() = EpisodeSegment.entries.firstOrNull { it.name == settings.episodesSegment } ?: EpisodeSegment.QUEUE

    fun remember(segment: EpisodeSegment) {
        settings.episodesSegment = segment.name
    }
}

/** [requestedSegment] is where a link into the tab wants to land; null means wherever the user left off. */
@Composable
fun EpisodesScreen(
    requestedSegment: EpisodeSegment?,
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
    queueViewModel: QueueViewModel = hiltViewModel(),
    downloadsViewModel: DownloadsViewModel = hiltViewModel(),
    episodesViewModel: EpisodesViewModel = hiltViewModel(),
) {
    val queueState by queueViewModel.uiState.collectAsStateWithLifecycle()
    val downloadsState by downloadsViewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(queueViewModel) {
        queueViewModel.events.collect {
            when (it) {
                is QueueEvent.Message -> snackbar.showSnackbar(it.text)
                is QueueEvent.QueueChanged -> {
                    val result = snackbar.showSnackbar(it.text, actionLabel = "Undo", duration = SnackbarDuration.Long)
                    if (result == SnackbarResult.ActionPerformed) queueViewModel.onIntent(QueueIntent.RestoreQueue(it.previous))
                }
            }
        }
    }
    LaunchedEffect(downloadsViewModel) {
        downloadsViewModel.events.collect { if (it is DownloadsEvent.Message) snackbar.showSnackbar(it.text) }
    }
    val actions = remember(queueViewModel) {
        EpisodeActionsUi(
            onPlay = { queueViewModel.onIntent(QueueIntent.Play(it.id)) },
            onPlayNext = { queueViewModel.onIntent(QueueIntent.PlayNext(it.id)) },
            onToggleQueue = { queueViewModel.onIntent(if (it.queued) QueueIntent.Dequeue(it.id) else QueueIntent.Enqueue(it.id)) },
            onDownload = { queueViewModel.onIntent(QueueIntent.Download(it.id)) },
            onDeleteDownload = { queueViewModel.onIntent(QueueIntent.DeleteDownload(it.id)) },
            onSetPlayed = { item, played -> queueViewModel.onIntent(QueueIntent.SetPlayed(item.id, played)) },
            onMarkOlderPlayed = { queueViewModel.onIntent(QueueIntent.RequestMarkOlderPlayed(it.id)) },
        )
    }

    var segment by rememberSaveable { mutableStateOf(requestedSegment ?: episodesViewModel.lastSegment) }
    EpisodesContent(
        segment = segment,
        onSegmentChange = { segment = it; episodesViewModel.remember(it) },
        queue = { QueueContent(queueState, actions, queueViewModel::onIntent) },
        new = { NewEpisodesContent(queueState, actions, queueViewModel::onIntent) },
        downloaded = { DownloadsContent(downloadsState, downloadsViewModel::onIntent) },
        modifier = modifier,
    )
}

/** The segment picker over whichever segment is chosen; the segments themselves are slots. */
@Composable
internal fun EpisodesContent(
    segment: EpisodeSegment,
    onSegmentChange: (EpisodeSegment) -> Unit,
    queue: @Composable () -> Unit,
    new: @Composable () -> Unit,
    downloaded: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        ScreenHeading("Episodes")
        val segments = EpisodeSegment.entries
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            segments.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = option == segment,
                    onClick = { onSegmentChange(option) },
                    shape = SegmentedButtonDefaults.itemShape(index, segments.size),
                ) { Text(option.label) }
            }
        }
        when (segment) {
            EpisodeSegment.QUEUE -> queue()
            EpisodeSegment.NEW -> new()
            EpisodeSegment.DOWNLOADED -> downloaded()
        }
    }
}
