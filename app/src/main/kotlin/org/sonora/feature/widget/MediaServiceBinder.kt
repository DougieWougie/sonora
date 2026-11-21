// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.widget

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.guava.await
import org.sonora.core.media.PodcastMediaSessionService

/**
 * Starts and binds [PodcastMediaSessionService] the way the activity does, so it attaches itself to the playback hub.
 * Close the result once playback has been asked for; the service keeps running as a foreground service while playing.
 */
@UnstableApi
internal suspend fun bindMediaService(context: Context): AutoCloseable {
    val token = SessionToken(context, ComponentName(context, PodcastMediaSessionService::class.java))
    val future = MediaController.Builder(context, token).buildAsync()
    try {
        future.await()
    } catch (e: Exception) {
        MediaController.releaseFuture(future)
        throw e
    }
    return AutoCloseable { MediaController.releaseFuture(future) }
}
