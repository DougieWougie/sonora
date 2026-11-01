// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.work

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog
import org.sonora.core.sync.NewEpisodes

@RunWith(AndroidJUnit4::class)
class AndroidNewEpisodeNotifierTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val posted get() = shadowOf(manager).allNotifications

    private fun episodes(podcastId: Long, vararg titles: String, description: String? = null) =
        NewEpisodes(podcastId, "Show $podcastId", titles.toList(), latestDescription = description)

    @Test
    fun `posts one notification per podcast under the new episodes channel`() = runBlocking {
        AndroidNewEpisodeNotifier(context).notify(listOf(episodes(1, "Pilot"), episodes(2, "Ep 2", "Ep 1")))

        assertEquals(2, posted.size)
        assertTrue(posted.all { it.channelId == NewEpisodeNotifications.CHANNEL_ID })
        assertEquals(
            setOf(NewEpisodeNotifications.idFor(1), NewEpisodeNotifications.idFor(2)),
            shadowOf(manager).activeNotifications.map { it.id }.toSet(),
        )
    }

    @Test
    fun `a single episode shows its title, the show and its description`() = runBlocking {
        AndroidNewEpisodeNotifier(context).notify(listOf(episodes(1, "Pilot", description = "<p>One. Two. Three.</p>")))

        val extras = posted.single().extras
        assertEquals("Pilot", extras.getString(Notification.EXTRA_TITLE))
        assertEquals("One. Two.", extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals("Show 1", extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString())
    }

    @Test
    fun `tapping opens the show's link in this app`() = runBlocking {
        AndroidNewEpisodeNotifier(context).notify(listOf(episodes(5, "Pilot")))

        val intent = shadowOf(posted.single().contentIntent).savedIntent
        assertEquals("sonora://podcast/5", intent.data.toString())
        assertEquals(context.packageName, intent.`package` ?: intent.component?.packageName)
    }

    @Test
    fun `a notification that cannot be built is logged and does not stop the others`() = runBlocking {
        ShadowLog.clear()
        val broken = NewEpisodes(1, "Broken", emptyList())

        AndroidNewEpisodeNotifier(context).notify(listOf(broken, episodes(2, "Fine")))

        assertEquals(listOf(NewEpisodeNotifications.idFor(2)), shadowOf(manager).activeNotifications.map { it.id })
        val warning = ShadowLog.getLogsForTag(AndroidNewEpisodeNotifier.TAG).single { it.type == Log.WARN }
        assertTrue(warning.msg, warning.msg.contains("1"))
        assertTrue(warning.throwable != null)
    }
}
