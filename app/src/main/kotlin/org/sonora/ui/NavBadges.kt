// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.sonora.core.database.EpisodeDao

/** What the navigation bar flags to the user: [episodes] is how many episodes are waiting unplayed. */
data class NavBadges(val episodes: Int = 0)

private const val MAX_BADGE = 99
private const val STOP_TIMEOUT_MS = 5_000L

/** The text for a badge, or null when there is nothing to flag. Big numbers are capped to fit the badge. */
internal fun badgeLabel(count: Int): String? = when {
    count <= 0 -> null
    count > MAX_BADGE -> "$MAX_BADGE+"
    else -> count.toString()
}

@HiltViewModel
class NavBadgesViewModel @Inject constructor(episodeDao: EpisodeDao) : ViewModel() {
    val badges: StateFlow<NavBadges> = episodeDao.observeUnplayedCount()
        .map { NavBadges(episodes = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), NavBadges())
}
