// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.download

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Keeps the count in SharedPreferences so the notice outlives the process that caused it. */
class PrefsEvictionNotices(context: Context) : EvictionNotices {
    private val prefs = context.applicationContext.getSharedPreferences("eviction_notices", Context.MODE_PRIVATE)
    private val count = MutableStateFlow(prefs.getInt(KEY_UNPLAYED, 0))

    override val unplayedRemoved: StateFlow<Int> = count

    // Retention runs on worker threads while the UI dismisses, so read-modify-write under one lock.
    @Synchronized
    override fun record(unplayedRemoved: Int) {
        if (unplayedRemoved > 0) set(count.value + unplayedRemoved)
    }

    @Synchronized
    override fun dismiss() = set(0)

    private fun set(value: Int) {
        prefs.edit().putInt(KEY_UNPLAYED, value).apply()
        count.value = value
    }

    private companion object {
        const val KEY_UNPLAYED = "unplayed_removed"
    }
}
