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
    fun sharesTheEpisodeAndShowAsPlainText() {
        val intent = shareIntent(PlayerEpisode(1, "The Episode", "The Podcast", null))

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals("The Episode — The Podcast", intent.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun sharesJustTheTitleWhenTheShowIsUnknown() {
        assertEquals("The Episode", shareIntent(PlayerEpisode(1, "The Episode", null, null)).getStringExtra(Intent.EXTRA_TEXT))
    }
}
