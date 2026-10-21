// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.discover

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.sonora.core.model.DirectoryPodcast

@RunWith(AndroidJUnit4::class)
class PrefsSuggestionStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences("suggestions", Context.MODE_PRIVATE)

    @Before
    fun clear() {
        prefs.edit().clear().commit()
    }

    @Test
    fun `nothing is stored at first`() {
        assertNull(PrefsSuggestionStore(context).load())
    }

    @Test
    fun `suggestions survive a restart`() {
        val saved = CachedSuggestions(
            42,
            listOf(DirectoryPodcast("A", "Author", "https://a", "https://img"), DirectoryPodcast("B", null, "https://b", null)),
        )
        PrefsSuggestionStore(context).save(saved)

        assertEquals(saved, PrefsSuggestionStore(context).load())
    }

    @Test
    fun `unreadable stored data is treated as empty`() {
        prefs.edit().putString("cache", "{not json").commit()
        assertNull(PrefsSuggestionStore(context).load())
    }
}
