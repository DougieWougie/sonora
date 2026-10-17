// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.ui.theme

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }

data class ThemePreferences(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val artworkColors: Boolean = true,
)

sealed interface ThemeIntent {
    data class SelectMode(val mode: ThemeMode) : ThemeIntent
    data object ToggleArtworkColors : ThemeIntent
}

fun reduce(preferences: ThemePreferences, intent: ThemeIntent): ThemePreferences = when (intent) {
    is ThemeIntent.SelectMode -> preferences.copy(mode = intent.mode)
    ThemeIntent.ToggleArtworkColors -> preferences.copy(artworkColors = !preferences.artworkColors)
}

data class ThemeState(
    val preferences: ThemePreferences = ThemePreferences(),
    /** Seed colour of the artwork currently on screen, if one has been extracted. */
    val artworkSeed: Int? = null,
) {
    val amoled: Boolean get() = preferences.mode == ThemeMode.AMOLED
    val effectiveSeed: Int? get() = artworkSeed.takeIf { preferences.artworkColors }

    fun isDark(systemDark: Boolean): Boolean = when (preferences.mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
    }
}

interface ThemePreferenceStore {
    val preferences: Flow<ThemePreferences>
    suspend fun save(preferences: ThemePreferences)
}

class ThemeStateHolder(private val store: ThemePreferenceStore, private val scope: CoroutineScope) {
    private val artworkSeed = MutableStateFlow<Int?>(null)

    val state: StateFlow<ThemeState> = combine(store.preferences, artworkSeed, ::ThemeState)
        .stateIn(scope, SharingStarted.Eagerly, ThemeState())

    fun dispatch(intent: ThemeIntent) {
        val reduced = reduce(state.value.preferences, intent)
        scope.launch { store.save(reduced) }
    }

    fun setArtworkSeed(seed: Int?) {
        artworkSeed.value = seed
    }
}
