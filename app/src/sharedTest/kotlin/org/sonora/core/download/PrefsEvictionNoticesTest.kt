// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrefsEvictionNoticesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clear() {
        context.getSharedPreferences("eviction_notices", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun startsWithNothingToReport() {
        assertEquals(0, PrefsEvictionNotices(context).unplayedRemoved.value)
    }

    @Test
    fun recordsAccumulate() {
        val notices = PrefsEvictionNotices(context)

        notices.record(2)
        notices.record(3)

        assertEquals(5, notices.unplayedRemoved.value)
    }

    @Test
    fun nonsenseCountsAreIgnored() {
        val notices = PrefsEvictionNotices(context)

        notices.record(0)
        notices.record(-4)

        assertEquals(0, notices.unplayedRemoved.value)
    }

    @Test
    fun theCountSurvivesARestart() {
        PrefsEvictionNotices(context).record(4)

        assertEquals(4, PrefsEvictionNotices(context).unplayedRemoved.value)
    }

    @Test
    fun dismissingClearsItForGood() {
        val notices = PrefsEvictionNotices(context)
        notices.record(4)

        notices.dismiss()

        assertEquals(0, notices.unplayedRemoved.value)
        assertEquals(0, PrefsEvictionNotices(context).unplayedRemoved.value)
    }
}
