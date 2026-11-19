// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
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
}

private fun Context.entryPoint() =
    EntryPointAccessors.fromApplication(applicationContext, MiniPlayerWidgetEntryPoint::class.java)

/** Glance runs callbacks on a background dispatcher, but the player may only be touched from Main. */
private suspend fun onPlayerThread(block: suspend () -> Unit) = withContext(Dispatchers.Main.immediate) { block() }

/** Lets tests drive the callbacks with a fake; null in the app, where they use the process-wide hub. */
internal var widgetPlaybackPort: ((Context) -> PlaybackPort)? = null

private fun Context.playbackPort(): PlaybackPort = widgetPlaybackPort?.invoke(this) ?: entryPoint().hub()

/** Home-screen mini player: current episode, artwork, previous / play-pause / next. */
class MiniPlayerWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entry = context.entryPoint()
        val states = miniPlayerWidgetStates(entry.hub().state, entry.episodeDao(), entry.podcastDao())
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
            GlanceTheme { MiniPlayerContent(state, artwork) }
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
internal fun MiniPlayerContent(state: MiniPlayerWidgetState, artwork: Bitmap?) {
    val context = LocalContext.current
    // MainActivity is singleTask, so this brings the existing task forward rather than starting another.
    val openApp = actionStartActivity<MainActivity>()
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .padding(horizontal = 12.dp, vertical = 8.dp)
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
                            modifier = GlanceModifier.size(48.dp).semantics { testTag = MINI_PLAYER_ARTWORK_TAG },
                        )
                        Spacer(GlanceModifier.width(12.dp))
                    }
                    Column(modifier = GlanceModifier.defaultWeight()) {
                        Text(
                            text = state.episodeTitle,
                            maxLines = 2,
                            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                        )
                        state.podcastTitle?.let {
                            Text(
                                text = it,
                                maxLines = 1,
                                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
                            )
                        }
                    }
                }
                // Five equal cells, each icon centred in its own.
                Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ControlButton(R.drawable.ic_widget_previous, context.getString(R.string.widget_previous), actionRunCallback<PreviousAction>())
                    ControlButton(R.drawable.ic_widget_replay_10, context.getString(R.string.widget_rewind), actionRunCallback<RewindAction>())
                    ControlButton(
                        if (state.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
                        context.getString(if (state.isPlaying) R.string.widget_pause else R.string.widget_play),
                        actionRunCallback<PlayPauseAction>(),
                    )
                    ControlButton(R.drawable.ic_widget_forward_30, context.getString(R.string.widget_forward), actionRunCallback<ForwardAction>())
                    ControlButton(R.drawable.ic_widget_next, context.getString(R.string.widget_next), actionRunCallback<NextAction>())
                }
            }
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

/** Toggles playback of whatever the player has loaded. The widget only offers it while the service is running. */
class PlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        onPlayerThread { context.playbackPort().togglePlayPause() }
    }
}

/** Pauses what is playing, or resumes what is loaded; does nothing when the player is empty. */
internal suspend fun PlaybackPort.togglePlayPause() {
    val current = state.first()
    val episodeId = current.episodeId ?: return
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
