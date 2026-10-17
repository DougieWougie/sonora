// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.work

import kotlinx.coroutines.flow.Flow

/** Starts an immediate background sync and reports whether one is queued or running. */
interface SyncController {
    val syncing: Flow<Boolean>
    fun syncNow()
}
