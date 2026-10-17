// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.ui.theme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class ThemeViewModel @Inject constructor(store: ThemePreferenceStore) : ViewModel() {
    private val holder = ThemeStateHolder(store, viewModelScope)
    val state: StateFlow<ThemeState> = holder.state

    fun onIntent(intent: ThemeIntent) = holder.dispatch(intent)
    fun onArtworkSeed(seed: Int?) = holder.setArtworkSeed(seed)
}
