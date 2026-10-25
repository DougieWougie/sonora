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
    fun `category and explicitness survive a restart`() {
        val saved = CachedSuggestions(1, listOf(DirectoryPodcast("A", null, "https://a", null, "News", explicit = true)))
        PrefsSuggestionStore(context).save(saved)

        assertEquals(saved, PrefsSuggestionStore(context).load())
    }

    @Test
    fun `a cache written before categories existed still loads`() {
        prefs.edit().putString(
            "cache",
            """{"fetchedAtMillis":1,"podcasts":[{"title":"A","author":null,"feedUrl":"https://a","artworkUrl":null}]}""",
        ).commit()

        assertEquals(DirectoryPodcast("A", null, "https://a", null), PrefsSuggestionStore(context).load()?.podcasts?.single())
    }

    @Test
    fun `unreadable stored data is treated as empty`() {
        prefs.edit().putString("cache", "{not json").commit()
        assertNull(PrefsSuggestionStore(context).load())
    }
}
