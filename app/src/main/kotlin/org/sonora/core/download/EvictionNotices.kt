// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.download

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Unplayed episodes that retention removed behind the user's back, kept until they dismiss the
 * notice. Played episodes going away is expected, so only unplayed ones are counted.
 */
interface EvictionNotices {
    /** Unplayed episodes removed since the notice was last dismissed. */
    val unplayedRemoved: StateFlow<Int>
    fun record(unplayedRemoved: Int)
    fun dismiss()
}

/** Remembers nothing; for callers that don't surface the notice. */
object NoEvictionNotices : EvictionNotices {
    override val unplayedRemoved: StateFlow<Int> = MutableStateFlow(0)
    override fun record(unplayedRemoved: Int) = Unit
    override fun dismiss() = Unit
}
