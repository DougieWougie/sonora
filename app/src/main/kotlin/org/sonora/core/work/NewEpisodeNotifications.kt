// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.sonora.R
import org.sonora.core.sync.NewEpisodeNotifier
import org.sonora.core.sync.NewEpisodes
import org.sonora.ui.PODCAST_LINK
import org.sonora.ui.htmlToSearchText

object NewEpisodeNotifications {
    const val CHANNEL_ID = "new_episodes"
    private const val BASE_ID = 0x6D00
    private const val ID_SPACE = 4096

    /** The description shown under the title while the notification is collapsed. */
    const val COLLAPSED_CHARS = 100

    /** Safety cap for the pulled-open description, for text with no sentence ends. */
    const val EXPANDED_CHARS = 300

    /** Both the collapsed and pulled-open description run to this many sentences. */
    const val EXPANDED_SENTENCES = 2

    /** Titles listed when several episodes arrive together; the rest are counted. */
    const val MAX_LINES = 5

    /**
     * What a notification says, apart from how Android draws it. [expandedText] is set for a single
     * episode with a description; [lines] and [moreCount] for several episodes.
     */
    data class Content(
        val title: String,
        val text: String,
        val subText: String?,
        val expandedText: String?,
        val lines: List<String>,
        val moreCount: Int,
    )

    /** One notification per podcast, so a fresh episode replaces that show's earlier notification. */
    fun idFor(podcastId: Long): Int = BASE_ID + Math.floorMod(podcastId, ID_SPACE.toLong()).toInt()

    /** Opens the podcast's page; handled by the navigation graph's deep link. */
    fun showLink(podcastId: Long): String = "$PODCAST_LINK/$podcastId"

    fun contentFor(newEpisodes: NewEpisodes): Content =
        if (newEpisodes.count == 1) {
            Content(
                title = newEpisodes.latestTitle,
                text = excerpt(newEpisodes.latestDescription, COLLAPSED_CHARS, EXPANDED_SENTENCES) ?: newEpisodes.podcastTitle,
                subText = newEpisodes.podcastTitle,
                expandedText = excerpt(newEpisodes.latestDescription, EXPANDED_CHARS, EXPANDED_SENTENCES),
                lines = emptyList(),
                moreCount = 0,
            )
        } else {
            Content(
                title = newEpisodes.podcastTitle,
                text = "${newEpisodes.count} new episodes · latest: ${newEpisodes.latestTitle}",
                subText = null,
                expandedText = null,
                lines = newEpisodes.titles.take(MAX_LINES),
                moreCount = (newEpisodes.count - MAX_LINES).coerceAtLeast(0),
            )
        }

    /**
     * Plain text of a feed's HTML: at most [sentences] sentences, then cut between words at [max]
     * characters. Null when there is nothing to show.
     */
    private fun excerpt(html: String?, max: Int, sentences: Int = Int.MAX_VALUE): String? {
        val plain = html?.let(::htmlToSearchText)?.takeIf { it.isNotBlank() } ?: return null
        val text = firstSentences(plain, sentences)
        if (text.length <= max) return text
        val head = text.take(max)
        return head.substringBeforeLast(' ', head).trimEnd() + "…"
    }

    /** A sentence ends at . ! or ? (and any closing quote or bracket) before a capital, digit or opening quote. */
    private val SENTENCE_END = Regex("""([.!?]["”')\]]*)\s+(?=["“'(\[]?[\p{Lu}\p{N}])""")

    private fun firstSentences(text: String, count: Int): String {
        val end = SENTENCE_END.findAll(text).drop(count - 1).firstOrNull() ?: return text
        return text.substring(0, end.groups[1]!!.range.last + 1)
    }
}

/**
 * Posts a notification per podcast with new episodes: the episode's (or show's) artwork as a
 * thumbnail, a line of its description that opens out when pulled down, and a tap that opens the
 * show. The launcher's icon dot or badge follows from these notifications, which is the only badge
 * API that works without a proprietary dependency.
 */
class AndroidNewEpisodeNotifier(private val context: Context) : NewEpisodeNotifier {
    override suspend fun notify(newEpisodes: List<NewEpisodes>) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        ensureChannel()
        newEpisodes.forEach { episodes ->
            // One podcast's failure must neither stop the others nor vanish: the sync pipeline ignores notifier errors.
            try {
                post(manager, episodes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not post the new-episode notification for podcast ${episodes.podcastId}", e)
            }
        }
    }

    private suspend fun post(manager: NotificationManagerCompat, episodes: NewEpisodes) {
        val content = NewEpisodeNotifications.contentFor(episodes)
        val builder = NotificationCompat.Builder(context, NewEpisodeNotifications.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setNumber(episodes.count)
            .setContentIntent(openShow(episodes.podcastId))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
        content.subText?.let(builder::setSubText)
        val picture = thumbnail(episodes.imageUrl)
        picture?.let(builder::setLargeIcon)
        when {
            // Artwork opens out as a big picture with the description beneath it; the small icon is then redundant.
            picture != null && content.lines.isEmpty() -> builder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(picture)
                    .bigLargeIcon(null as Bitmap?)
                    .setSummaryText(content.expandedText),
            )
            content.expandedText != null -> builder.setStyle(NotificationCompat.BigTextStyle().bigText(content.expandedText))
            content.lines.isNotEmpty() -> builder.setStyle(
                NotificationCompat.InboxStyle().also { inbox ->
                    content.lines.forEach(inbox::addLine)
                    if (content.moreCount > 0) inbox.setSummaryText("+${content.moreCount} more")
                },
            )
        }
        try {
            manager.notify(NewEpisodeNotifications.idFor(episodes.podcastId), builder.build())
        } catch (e: SecurityException) {
            // Permission revoked since the areNotificationsEnabled check in notify().
            Log.w(TAG, "Notification permission was revoked before podcast ${episodes.podcastId} could be announced", e)
        }
    }

    /** An explicit intent to this app's launcher activity carrying the podcast's link, so nothing else can claim it. */
    private fun openShow(podcastId: Long): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        launch.action = Intent.ACTION_VIEW
        launch.data = NewEpisodeNotifications.showLink(podcastId).toUri()
        return PendingIntent.getActivity(
            context,
            NewEpisodeNotifications.idFor(podcastId),
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** Through the app's image loader, so artwork follows the same HTTPS-only policy. Never holds the notification up for long. */
    private suspend fun thumbnail(url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        return try {
            withTimeoutOrNull(THUMBNAIL_TIMEOUT_MS) {
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .size(Size(THUMBNAIL_PX, THUMBNAIL_PX))
                    .allowHardware(false)
                    .build()
                (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
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

    companion object {
        const val TAG = "NewEpisodeNotifier"

        /** Big enough for the expanded picture; the system scales it down for the small thumbnail. */
        private const val THUMBNAIL_PX = 512
        private const val THUMBNAIL_TIMEOUT_MS = 5_000L
    }
}
