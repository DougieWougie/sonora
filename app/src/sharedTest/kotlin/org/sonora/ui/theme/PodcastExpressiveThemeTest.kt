// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.ui.theme

import androidx.activity.ComponentActivity
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PodcastExpressiveThemeTest {
    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val seed = 0xFFD2452B.toInt()

    private fun themed(state: ThemeState): ColorScheme {
        lateinit var scheme: ColorScheme
        composeTestRule.setContent { PodcastExpressiveTheme(state) { scheme = MaterialTheme.colorScheme } }
        // Let the cross-fade between palettes settle.
        composeTestRule.mainClock.advanceTimeBy(5_000)
        composeTestRule.waitForIdle()
        return scheme
    }

    private fun expected(dark: Boolean, amoled: Boolean = false) =
        ColorRoles.fromSeed(seed, dark, amoled).toColorScheme(dark)

    private fun state(mode: ThemeMode = ThemeMode.LIGHT, artworkColors: Boolean = true, artworkSeed: Int? = seed) =
        ThemeState(ThemePreferences(mode, artworkColors), artworkSeed)

    @Test
    fun `artwork colours drive a light scheme`() {
        val scheme = themed(state(ThemeMode.LIGHT))

        assertEquals(expected(dark = false).primary, scheme.primary)
        assertEquals(expected(dark = false).surface, scheme.surface)
    }

    @Test
    fun `artwork colours drive a dark scheme`() {
        val scheme = themed(state(ThemeMode.DARK))

        assertEquals(expected(dark = true).primary, scheme.primary)
        assertNotEquals(expected(dark = false).surface, scheme.surface)
    }

    @Test
    fun `amoled uses the amoled palette`() {
        val scheme = themed(state(ThemeMode.AMOLED))

        assertEquals(expected(dark = true, amoled = true).background, scheme.background)
    }

    @Test
    fun `amoled without artwork still goes black`() {
        val scheme = themed(state(ThemeMode.AMOLED, artworkSeed = null))

        assertEquals(Color.Black, scheme.background)
        assertEquals(Color.Black, scheme.surface)
    }

    @Test
    fun `turning artwork colours off ignores the artwork seed`() {
        val scheme = themed(state(artworkColors = false))

        assertNotEquals(expected(dark = false).primary, scheme.primary)
    }

    @Test
    fun `the default theme resolves a scheme without artwork`() {
        val scheme = themed(ThemeState())

        assertNotEquals(Color.Unspecified, scheme.primary)
    }

    @Test
    fun `the typography uses bold headlines and tabular label figures`() {
        lateinit var typography: Typography
        composeTestRule.setContent { PodcastExpressiveTheme { typography = MaterialTheme.typography } }
        composeTestRule.waitForIdle()

        assertEquals(FontWeight.Bold, typography.headlineLargeEmphasized.fontWeight)
        assertEquals(TabularNumbers, typography.labelLargeEmphasized.fontFeatureSettings)
    }

    @Test
    fun `tabular switches on tabular figures`() {
        assertEquals(TabularNumbers, TextStyle().tabular().fontFeatureSettings)
    }

    @Test
    fun `a dark colour scheme differs from a light one`() {
        assertNotEquals(expected(dark = true).background, expected(dark = false).background)
        assertEquals(expected(dark = true).primaryContainer, expected(dark = true).inversePrimary)
    }
}
