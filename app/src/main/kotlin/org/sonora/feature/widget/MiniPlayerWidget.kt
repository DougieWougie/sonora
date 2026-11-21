// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.ContentScale
import androidx.glance.layout.RowScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.semantics.semantics
import androidx.glance.semantics.testTag
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.sonora.MainActivity
import org.sonora.R
import org.sonora.core.database.EpisodeDao
import org.sonora.core.database.PodcastDao
import org.sonora.core.media.PlaybackHub
import org.sonora.core.media.PlayQueue
import org.sonora.core.media.PlaybackPort
import org.sonora.core.media.PlaybackProgress
import org.sonora.core.media.SEEK_BACK_MS
import org.sonora.core.media.SEEK_FORWARD_MS

@EntryPoint
@InstallIn(SingletonComponent::class)
interface MiniPlayerWidgetEntryPoint {
    fun hub(): PlaybackHub
    fun episodeDao(): EpisodeDao
    fun podcastDao(): PodcastDao
    fun playQueue(): PlayQueue
    fun lastPlayed(): LastPlayedStore
}

private fun Context.entryPoint() =
    EntryPointAccessors.fromApplication(applicationContext, MiniPlayerWidgetEntryPoint::class.java)

/** Glance runs callbacks on a background dispatcher, but the player may only be touched from Main. */
private suspend fun onPlayerThread(block: suspend () -> Unit) = withContext(Dispatchers.Main.immediate) { block() }

/** Lets tests drive the callbacks with a fake; null in the app, where they use the process-wide hub. */
internal var widgetPlaybackPort: ((Context) -> PlaybackPort)? = null

private fun Context.playbackPort(): PlaybackPort = widgetPlaybackPort?.invoke(this) ?: entryPoint().hub()

/** What the widget draws at a given size: 2x2 is the player alone, 4x4 adds the queue. */
enum class WidgetLayout { Compact, Large }

private val COMPACT_SIZE = DpSize(110.dp, 110.dp)
private val LARGE_SIZE = DpSize(250.dp, 250.dp)

/** The queue needs room for the player and a few rows, so a wide but short widget stays compact. */
internal fun layoutFor(size: DpSize): WidgetLayout =
    if (size.width >= LARGE_SIZE.width - 30.dp && size.height >= LARGE_SIZE.height - 30.dp) WidgetLayout.Large else WidgetLayout.Compact

/** Home-screen mini player: current episode and artwork with controls, plus the queue when it is large enough. */
class MiniPlayerWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(COMPACT_SIZE, LARGE_SIZE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entry = context.entryPoint()
        val states = miniPlayerWidgetStates(entry.hub().state, entry.playQueue().items, entry.episodeDao(), entry.podcastDao(), entry.lastPlayed())
        // Draw the real state first so the widget never flashes "Nothing playing" on the way in.
        val first = states.first()
        val firstUrl = (first as? MiniPlayerWidgetState.Loaded)?.artworkUrl
        val firstArtwork = firstUrl?.let { loadArtwork(context, it) }
        provideContent {
            // While a session is running Glance re-composes on update() without calling provideGlance again, so
            // the state has to be collected here to stay live.
            val state by states.collectAsState(initial = first)
            val url = (state as? MiniPlayerWidgetState.Loaded)?.artworkUrl
            val artwork by produceState(initialValue = firstArtwork.takeIf { url == firstUrl }, url) {
                value = url?.let { loadArtwork(context, it) }
            }
            GlanceTheme { MiniPlayerContent(state, artwork, layoutFor(LocalSize.current)) }
        }
    }
}

/** Lets tests find the artwork image. */
internal const val MINI_PLAYER_ARTWORK_TAG = "mini-player-artwork"

private const val ARTWORK_PX = 256
private const val ARTWORK_TIMEOUT_MS = 5_000L

private suspend fun loadArtwork(context: Context, url: String): Bitmap? = withTimeoutOrNull(ARTWORK_TIMEOUT_MS) {
    val request = ImageRequest.Builder(context).data(url).size(ARTWORK_PX).allowHardware(false).build()
    SingletonImageLoader.get(context).execute(request).image?.toBitmap()
}

@Composable
internal fun MiniPlayerContent(state: MiniPlayerWidgetState, artwork: Bitmap?, layout: WidgetLayout = WidgetLayout.Large) {
    val context = LocalContext.current
    val compact = layout == WidgetLayout.Compact
    // MainActivity is singleTask, so this brings the existing task forward rather than starting another.
    val openApp = actionStartActivity<MainActivity>()
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .padding(horizontal = if (compact) 8.dp else 12.dp, vertical = 8.dp)
            .clickable(openApp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (state) {
            MiniPlayerWidgetState.Idle -> Text(
                text = context.getString(R.string.widget_idle),
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp),
            )
            is MiniPlayerWidgetState.Loaded -> {
                Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (artwork != null) {
                        Image(
                            provider = ImageProvider(artwork),
                            contentDescription = null,
                            modifier = GlanceModifier.size(if (compact) 36.dp else 48.dp).semantics { testTag = MINI_PLAYER_ARTWORK_TAG },
                        )
                        Spacer(GlanceModifier.width(if (compact) 8.dp else 12.dp))
                    }
                    Column(modifier = GlanceModifier.defaultWeight()) {
                        Text(
                            text = state.episodeTitle,
                            maxLines = 2,
                            style = TextStyle(
                                color = GlanceTheme.colors.onSurface,
                                fontSize = if (compact) 12.sp else 14.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                        )
                        // The 2x2 has no room for a third line.
                        if (!compact) {
                            state.podcastTitle?.let {
                                Text(
                                    text = it,
                                    maxLines = 1,
                                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
                                )
                            }
                        }
                    }
                }
                // Equal cells, each icon centred in its own.
                Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val playPause = ControlSpec(
                        if (state.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
                        if (state.isPlaying) R.string.widget_pause else R.string.widget_play,
                        // An episode the player has let go of is started from the id the widget remembers.
                        actionRunCallback<PlayPauseAction>(
                            if (state.active) actionParametersOf() else actionParametersOf(RECALLED_EPISODE_KEY to state.episodeId),
                        ),
                    )
                    val controls = when {
                        !state.active -> listOf(playPause)
                        compact -> listOf(rewind, playPause, forward)
                        else -> listOf(previous, rewind, playPause, forward, next)
                    }
                    controls.forEach { ControlButton(it.icon, context.getString(it.description), it.action) }
                }
                if (!compact && state.active && state.queue.isNotEmpty()) Queue(state)
            }
        }
    }
}

private class ControlSpec(val icon: Int, val description: Int, val action: androidx.glance.action.Action)

private val previous get() = ControlSpec(R.drawable.ic_widget_previous, R.string.widget_previous, actionRunCallback<PreviousAction>())
private val rewind get() = ControlSpec(R.drawable.ic_widget_replay_10, R.string.widget_rewind, actionRunCallback<RewindAction>())
private val forward get() = ControlSpec(R.drawable.ic_widget_forward_30, R.string.widget_forward, actionRunCallback<ForwardAction>())
private val next get() = ControlSpec(R.drawable.ic_widget_next, R.string.widget_next, actionRunCallback<NextAction>())

@Composable
private fun Queue(state: MiniPlayerWidgetState.Loaded) {
    val context = LocalContext.current
    Column(modifier = GlanceModifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            text = context.getString(R.string.widget_up_next),
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Medium),
        )
        state.queue.forEach {
            Text(
                text = it.title,
                maxLines = 1,
                modifier = GlanceModifier.padding(top = 2.dp),
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp),
            )
        }
        val more = state.queueSize - state.queue.size
        if (more > 0) {
            Text(
                text = context.getString(R.string.widget_queue_more, more),
                modifier = GlanceModifier.padding(top = 2.dp),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
            )
        }
    }
}

@Composable
private fun RowScope.ControlButton(icon: Int, description: String, onClick: androidx.glance.action.Action) {
    Image(
        provider = ImageProvider(icon),
        contentDescription = description,
        contentScale = ContentScale.Fit,
        colorFilter = androidx.glance.ColorFilter.tint(GlanceTheme.colors.onSurface),
        modifier = GlanceModifier.defaultWeight().height(40.dp).padding(8.dp).clickable(onClick),
    )
}

/** The episode the widget offers while the player is empty, passed with the click that starts it. */
internal val RECALLED_EPISODE_KEY = ActionParameters.Key<Long>("recalled-episode")

/** Starts the media service when nothing has yet; null in the app, where it is bound the way the activity does. */
internal var widgetServiceStarter: (suspend (Context) -> AutoCloseable)? = null

/**
 * Toggles playback of whatever the player has loaded. With nothing loaded it plays the episode the widget was
 * showing, starting the media service first, because only the activity otherwise does.
 */
@androidx.media3.common.util.UnstableApi // references bindMediaService, which uses Media3 @UnstableApi
class PlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val recalled = parameters[RECALLED_EPISODE_KEY]
        onPlayerThread {
            val port = context.playbackPort()
            val binding = if (recalled != null && port.state.first().episodeId == null) {
                (widgetServiceStarter ?: ::bindMediaService).invoke(context)
            } else {
                null
            }
            try {
                port.togglePlayPause(recalled)
            } finally {
                binding?.close()
            }
        }
    }
}

/** Pauses what is playing, or resumes what is loaded (or [recalled], when nothing is); does nothing otherwise. */
internal suspend fun PlaybackPort.togglePlayPause(recalled: Long? = null) {
    val current = state.first()
    val episodeId = current.episodeId ?: recalled ?: return
    if (current.isPlaying) pause() else play(episodeId)
}

/** The same intervals as the notification and lock-screen controls. */
internal const val REWIND_MS = SEEK_BACK_MS
internal const val FORWARD_MS = SEEK_FORWARD_MS

/** Moves the playhead by [deltaMs], staying inside the episode; does nothing when nothing is loaded. */
internal suspend fun PlaybackPort.seekBy(deltaMs: Long) {
    val now = progress().first()
    if (now == PlaybackProgress.None) return
    val target = now.positionMs + deltaMs
    seekTo(if (now.durationMs > 0) target.coerceIn(0, now.durationMs) else target.coerceAtLeast(0))
}

class RewindAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        onPlayerThread { context.playbackPort().seekBy(-REWIND_MS) }
    }
}

class ForwardAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        onPlayerThread { context.playbackPort().seekBy(FORWARD_MS) }
    }
}

class PreviousAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        onPlayerThread { context.playbackPort().previous() }
    }
}

class NextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        onPlayerThread { context.playbackPort().next() }
    }
}

class MiniPlayerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MiniPlayerWidget()
}
