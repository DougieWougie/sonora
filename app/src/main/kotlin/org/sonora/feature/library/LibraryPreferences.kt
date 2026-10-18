// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.library

import kotlinx.coroutines.flow.StateFlow

/** Library display choices that should survive the app being closed. */
interface LibraryPreferences {
    val layout: StateFlow<LibraryLayout>
    fun setLayout(layout: LibraryLayout)
}
