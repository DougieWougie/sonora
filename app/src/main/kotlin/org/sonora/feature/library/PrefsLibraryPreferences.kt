// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.library

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class PrefsLibraryPreferences(context: Context) : LibraryPreferences {
    private val prefs = context.applicationContext.getSharedPreferences("library", Context.MODE_PRIVATE)
    private val current = MutableStateFlow(
        runCatching { LibraryLayout.valueOf(prefs.getString(KEY_LAYOUT, null).orEmpty()) }.getOrDefault(LibraryLayout.GRID),
    )

    override val layout: StateFlow<LibraryLayout> = current

    override fun setLayout(layout: LibraryLayout) {
        prefs.edit().putString(KEY_LAYOUT, layout.name).apply()
        current.value = layout
    }

    private companion object {
        const val KEY_LAYOUT = "layout"
    }
}
