// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.library

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
class PrefsLibraryPreferencesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clear() {
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun defaultsToTheGrid() {
        assertEquals(LibraryLayout.GRID, PrefsLibraryPreferences(context).layout.value)
    }

    @Test
    fun theChosenLayoutIsPublishedAndSurvivesARestart() {
        val prefs = PrefsLibraryPreferences(context)

        prefs.setLayout(LibraryLayout.LIST)

        assertEquals(LibraryLayout.LIST, prefs.layout.value)
        assertEquals(LibraryLayout.LIST, PrefsLibraryPreferences(context).layout.value)
    }

    @Test
    fun anUnrecognisedStoredValueFallsBackToTheGrid() {
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().putString("layout", "CAROUSEL").commit()

        assertEquals(LibraryLayout.GRID, PrefsLibraryPreferences(context).layout.value)
    }

    @Test
    fun sortAndFilterDefaultToTitleAndAllEpisodes() {
        val prefs = PrefsLibraryPreferences(context)

        assertEquals(LibrarySort.TITLE, prefs.sort.value)
        assertFalse(prefs.unplayedOnly.value)
    }

    @Test
    fun theChosenSortAndFilterArePublishedAndSurviveARestart() {
        val prefs = PrefsLibraryPreferences(context)

        prefs.setSort(LibrarySort.UNPLAYED)
        prefs.setUnplayedOnly(true)

        assertEquals(LibrarySort.UNPLAYED, prefs.sort.value)
        assertTrue(prefs.unplayedOnly.value)
        val restarted = PrefsLibraryPreferences(context)
        assertEquals(LibrarySort.UNPLAYED, restarted.sort.value)
        assertTrue(restarted.unplayedOnly.value)
    }

    @Test
    fun anUnrecognisedStoredSortFallsBackToTitle() {
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().putString("sort", "SHUFFLE").commit()

        assertEquals(LibrarySort.TITLE, PrefsLibraryPreferences(context).sort.value)
    }
}
