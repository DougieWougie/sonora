// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import org.sonora.R

object DownloadNotifications {
    const val CHANNEL_ID = "downloads"
    private const val BASE_ID = 0x5D00
    private const val ID_SPACE = 4096

    /** One notification per episode, so parallel downloads don't overwrite each other's progress. */
    fun idFor(episodeId: Long): Int = BASE_ID + Math.floorMod(episodeId, ID_SPACE.toLong()).toInt()

    fun build(context: Context, title: String, bytes: Long, total: Long?): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.download_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (total != null && total > 0) {
            builder.setProgress(PROGRESS_MAX, (bytes * PROGRESS_MAX / total).toInt(), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private const val PROGRESS_MAX = 1000
}
