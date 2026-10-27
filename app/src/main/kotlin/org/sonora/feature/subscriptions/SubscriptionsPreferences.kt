// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.subscriptions

import kotlinx.coroutines.flow.StateFlow

/** Library display choices that should survive the app being closed. */
interface SubscriptionsPreferences {
    val layout: StateFlow<SubscriptionsLayout>
    val sort: StateFlow<SubscriptionsSort>
    val unplayedOnly: StateFlow<Boolean>
    fun setLayout(layout: SubscriptionsLayout)
    fun setSort(sort: SubscriptionsSort)
    fun setUnplayedOnly(enabled: Boolean)
}
