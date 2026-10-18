// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.library

import kotlinx.coroutines.flow.StateFlow

/** Library display choices that should survive the app being closed. */
interface LibraryPreferences {
    val layout: StateFlow<LibraryLayout>
    val sort: StateFlow<LibrarySort>
    val unplayedOnly: StateFlow<Boolean>
    fun setLayout(layout: LibraryLayout)
    fun setSort(sort: LibrarySort)
    fun setUnplayedOnly(enabled: Boolean)
}
