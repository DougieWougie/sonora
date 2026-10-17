// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.ui.theme

import app.cash.turbine.test
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FakeThemeStore(initial: ThemePreferences = ThemePreferences()) : ThemePreferenceStore {
    override val preferences = MutableStateFlow(initial)
    override suspend fun save(preferences: ThemePreferences) { this.preferences.value = preferences }
}

class ThemePreferencesReducerTest {
    @Test
    fun `defaults follow the system and use artwork colors`() {
        val defaults = ThemePreferences()
        assertEquals(ThemeMode.SYSTEM, defaults.mode)
        assertTrue(defaults.artworkColors)
    }

    @Test
    fun `selecting a mode replaces only the mode`() {
        val before = ThemePreferences(ThemeMode.LIGHT, artworkColors = false)
        assertEquals(
            ThemePreferences(ThemeMode.AMOLED, artworkColors = false),
            reduce(before, ThemeIntent.SelectMode(ThemeMode.AMOLED)),
        )
    }

    @Test
    fun `toggling artwork colors flips only the flag`() {
        val before = ThemePreferences(ThemeMode.DARK, artworkColors = true)
        assertEquals(ThemePreferences(ThemeMode.DARK, false), reduce(before, ThemeIntent.ToggleArtworkColors))
        assertEquals(before, reduce(reduce(before, ThemeIntent.ToggleArtworkColors), ThemeIntent.ToggleArtworkColors))
    }

    @Test
    fun `dark resolution depends on mode and system setting`() {
        fun dark(mode: ThemeMode, system: Boolean) = ThemeState(ThemePreferences(mode)).isDark(system)
        assertTrue(dark(ThemeMode.SYSTEM, true))
        assertFalse(dark(ThemeMode.SYSTEM, false))
        assertFalse(dark(ThemeMode.LIGHT, true))
        assertTrue(dark(ThemeMode.DARK, false))
        assertTrue(dark(ThemeMode.AMOLED, false))
    }

    @Test
    fun `amoled is its own pure-black dark variant`() {
        assertTrue(ThemeState(ThemePreferences(ThemeMode.AMOLED)).amoled)
        assertFalse(ThemeState(ThemePreferences(ThemeMode.DARK)).amoled)
    }

    @Test
    fun `the artwork seed only applies when artwork colors are on`() {
        val on = ThemeState(ThemePreferences(artworkColors = true), artworkSeed = 0xFF336699.toInt())
        val off = on.copy(preferences = ThemePreferences(artworkColors = false))
        assertEquals(0xFF336699.toInt(), on.effectiveSeed)
        assertNull(off.effectiveSeed)
    }
}

class ThemeStateHolderTest {
    @Test
    fun `dispatching an intent persists the reduced preferences and updates state`() = runTest {
        val store = FakeThemeStore()
        val holder = ThemeStateHolder(store, backgroundScope)

        holder.state.test {
            assertEquals(ThemeMode.SYSTEM, awaitItem().preferences.mode)
            holder.dispatch(ThemeIntent.SelectMode(ThemeMode.DARK))
            assertEquals(ThemeMode.DARK, awaitItem().preferences.mode)
            assertEquals(ThemeMode.DARK, store.preferences.value.mode)
        }
    }

    @Test
    fun `loads stored preferences`() = runTest {
        val store = FakeThemeStore(ThemePreferences(ThemeMode.LIGHT, artworkColors = false))
        val holder = ThemeStateHolder(store, backgroundScope)

        holder.state.test {
            var item = awaitItem()
            if (item.preferences.mode != ThemeMode.LIGHT) item = awaitItem()
            assertEquals(ThemePreferences(ThemeMode.LIGHT, false), item.preferences)
        }
    }

    @Test
    fun `artwork seed changes flow into the state and can be cleared`() = runTest {
        val holder = ThemeStateHolder(FakeThemeStore(), backgroundScope)

        holder.state.test {
            awaitItem()
            holder.setArtworkSeed(0xFFAA3300.toInt())
            assertEquals(0xFFAA3300.toInt(), awaitItem().effectiveSeed)
            holder.setArtworkSeed(null)
            assertNull(awaitItem().effectiveSeed)
        }
    }
}
