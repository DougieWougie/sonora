// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.subscriptions

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrefsSubscriptionsPreferencesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clear() {
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun defaultsToTheGrid() {
        assertEquals(SubscriptionsLayout.GRID, PrefsSubscriptionsPreferences(context).layout.value)
    }

    @Test
    fun theChosenLayoutIsPublishedAndSurvivesARestart() {
        val prefs = PrefsSubscriptionsPreferences(context)

        prefs.setLayout(SubscriptionsLayout.LIST)

        assertEquals(SubscriptionsLayout.LIST, prefs.layout.value)
        assertEquals(SubscriptionsLayout.LIST, PrefsSubscriptionsPreferences(context).layout.value)
    }

    @Test
    fun anUnrecognisedStoredValueFallsBackToTheGrid() {
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().putString("layout", "CAROUSEL").commit()

        assertEquals(SubscriptionsLayout.GRID, PrefsSubscriptionsPreferences(context).layout.value)
    }

    @Test
    fun sortAndFilterDefaultToTitleAndAllEpisodes() {
        val prefs = PrefsSubscriptionsPreferences(context)

        assertEquals(SubscriptionsSort.TITLE, prefs.sort.value)
        assertFalse(prefs.unplayedOnly.value)
    }

    @Test
    fun theChosenSortAndFilterArePublishedAndSurviveARestart() {
        val prefs = PrefsSubscriptionsPreferences(context)

        prefs.setSort(SubscriptionsSort.UNPLAYED)
        prefs.setUnplayedOnly(true)

        assertEquals(SubscriptionsSort.UNPLAYED, prefs.sort.value)
        assertTrue(prefs.unplayedOnly.value)
        val restarted = PrefsSubscriptionsPreferences(context)
        assertEquals(SubscriptionsSort.UNPLAYED, restarted.sort.value)
        assertTrue(restarted.unplayedOnly.value)
    }

    @Test
    fun anUnrecognisedStoredSortFallsBackToTitle() {
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().putString("sort", "SHUFFLE").commit()

        assertEquals(SubscriptionsSort.TITLE, PrefsSubscriptionsPreferences(context).sort.value)
    }
}
