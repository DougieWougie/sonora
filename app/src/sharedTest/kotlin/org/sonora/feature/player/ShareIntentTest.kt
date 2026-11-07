// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.player

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShareIntentTest {
    @Test
    fun sendsTheTextAsPlainTextWithTheTitleAsSubject() {
        val intent = shareIntent(PlayerEvent.Share("The Episode", "The Episode — The Podcast\nhttps://example.org/ep"))

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals("The Episode", intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals("The Episode — The Podcast\nhttps://example.org/ep", intent.getStringExtra(Intent.EXTRA_TEXT))
    }
}
