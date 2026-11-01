// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.sonora.R
import org.sonora.core.sync.NewEpisodeNotifier
import org.sonora.core.sync.NewEpisodes

object NewEpisodeNotifications {
    const val CHANNEL_ID = "new_episodes"
    private const val BASE_ID = 0x6D00
    private const val ID_SPACE = 4096

    data class Text(val title: String, val body: String)

    /** One notification per podcast, so a fresh episode replaces that show's earlier notification. */
    fun idFor(podcastId: Long): Int = BASE_ID + Math.floorMod(podcastId, ID_SPACE.toLong()).toInt()

    fun textFor(newEpisodes: NewEpisodes) = Text(
        title = newEpisodes.podcastTitle,
        body = if (newEpisodes.count == 1) newEpisodes.latestTitle
        else "${newEpisodes.count} new episodes · latest: ${newEpisodes.latestTitle}",
    )
}

/**
 * Posts a notification per podcast with new episodes. The launcher's icon dot or badge follows from
 * these notifications, which is the only badge API that works without a proprietary dependency.
 */
class AndroidNewEpisodeNotifier(private val context: Context) : NewEpisodeNotifier {
    override suspend fun notify(newEpisodes: List<NewEpisodes>) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        ensureChannel()
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        newEpisodes.forEach { episodes ->
            val text = NewEpisodeNotifications.textFor(episodes)
            val notification = NotificationCompat.Builder(context, NewEpisodeNotifications.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(text.title)
                .setContentText(text.body)
                .setNumber(episodes.count)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .build()
            try {
                manager.notify(NewEpisodeNotifications.idFor(episodes.podcastId), notification)
            } catch (_: SecurityException) {
                // Permission revoked between the check and the post: nothing to tell the user about.
            }
        }
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(NewEpisodeNotifications.CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    NewEpisodeNotifications.CHANNEL_ID,
                    context.getString(R.string.new_episodes_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
    }
}
