// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.subscriptions

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class PrefsSubscriptionsPreferences(context: Context) : SubscriptionsPreferences {
    private val prefs = context.applicationContext.getSharedPreferences("library", Context.MODE_PRIVATE)
    private val currentLayout = MutableStateFlow(readEnum(KEY_LAYOUT, SubscriptionsLayout.GRID))
    private val currentSort = MutableStateFlow(readEnum(KEY_SORT, SubscriptionsSort.TITLE))
    private val currentUnplayedOnly = MutableStateFlow(prefs.getBoolean(KEY_UNPLAYED_ONLY, false))

    override val layout: StateFlow<SubscriptionsLayout> = currentLayout
    override val sort: StateFlow<SubscriptionsSort> = currentSort
    override val unplayedOnly: StateFlow<Boolean> = currentUnplayedOnly

    override fun setLayout(layout: SubscriptionsLayout) {
        prefs.edit().putString(KEY_LAYOUT, layout.name).apply()
        currentLayout.value = layout
    }

    override fun setSort(sort: SubscriptionsSort) {
        prefs.edit().putString(KEY_SORT, sort.name).apply()
        currentSort.value = sort
    }

    override fun setUnplayedOnly(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_UNPLAYED_ONLY, enabled).apply()
        currentUnplayedOnly.value = enabled
    }

    private inline fun <reified E : Enum<E>> readEnum(key: String, default: E): E =
        runCatching { enumValueOf<E>(prefs.getString(key, null).orEmpty()) }.getOrDefault(default)

    private companion object {
        const val KEY_LAYOUT = "layout"
        const val KEY_SORT = "sort"
        const val KEY_UNPLAYED_ONLY = "unplayed_only"
    }
}
