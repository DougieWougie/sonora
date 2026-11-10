// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.settings

enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }

/** The theme choices that are stored; how they are applied is up to the UI. */
data class ThemePreferences(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val artworkColors: Boolean = true,
)
