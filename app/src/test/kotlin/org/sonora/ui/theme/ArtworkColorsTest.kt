// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.ui.theme

import kotlin.math.max
import kotlin.math.min
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private fun luminance(argb: Int): Double {
    fun channel(shift: Int): Double {
        val c = ((argb shr shift) and 0xFF) / 255.0
        return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
}

internal fun contrast(a: Int, b: Int): Double {
    val la = luminance(a)
    val lb = luminance(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
}

class SeedColorSelectorTest {
    private val red = ColorSample(0xFFD32F2F.toInt(), population = 100)
    private val blueSmall = ColorSample(0xFF1976D2.toInt(), population = 10)
    private val grayHuge = ColorSample(0xFF808080.toInt(), population = 10_000)

    @Test
    fun `no samples gives no seed`() {
        assertNull(SeedColorSelector.pick(emptyList()))
    }

    @Test
    fun `prefers a vivid colour over a dominant grey`() {
        assertEquals(red.argb, SeedColorSelector.pick(listOf(grayHuge, red))?.argb)
    }

    @Test
    fun `weights vivid colours by how much of the image they cover`() {
        assertEquals(red.argb, SeedColorSelector.pick(listOf(blueSmall, red))?.argb)
    }

    @Test
    fun `falls back to the most common colour when everything is grey`() {
        val darkGrey = ColorSample(0xFF303030.toInt(), 50)
        assertEquals(grayHuge.argb, SeedColorSelector.pick(listOf(darkGrey, grayHuge))?.argb)
    }

    @Test
    fun `the seed is always opaque`() {
        val translucent = ColorSample(0x80D32F2F.toInt(), 5)
        assertEquals(0xFF, SeedColorSelector.pick(listOf(translucent))!!.argb ushr 24)
    }
}

class ColorRolesTest {
    private val seeds = listOf(0xFFD32F2F, 0xFFFFEB3B, 0xFF1976D2, 0xFF2E7D32, 0xFF9C27B0, 0xFF808080, 0xFFFFFFFF, 0xFF000000)
        .map { it.toInt() }

    private fun pairs(r: ColorRoles) = listOf(
        r.primary to r.onPrimary,
        r.primaryContainer to r.onPrimaryContainer,
        r.secondary to r.onSecondary,
        r.secondaryContainer to r.onSecondaryContainer,
        r.tertiary to r.onTertiary,
        r.tertiaryContainer to r.onTertiaryContainer,
        r.background to r.onBackground,
        r.surface to r.onSurface,
        r.surfaceVariant to r.onSurfaceVariant,
    )

    @Test
    fun `every foreground role has AA contrast against its background in light, dark and amoled`() {
        for (seed in seeds) for ((dark, amoled) in listOf(false to false, true to false, true to true)) {
            for ((bg, fg) in pairs(ColorRoles.fromSeed(seed, dark, amoled))) {
                assertTrue(contrast(bg, fg) >= 4.5, "seed=${seed.toString(16)} dark=$dark amoled=$amoled ${contrast(bg, fg)}")
            }
        }
    }

    @Test
    fun `amoled uses a pure black background`() {
        val roles = ColorRoles.fromSeed(0xFF1976D2.toInt(), dark = true, amoled = true)
        assertEquals(0xFF000000.toInt(), roles.background)
        assertEquals(0xFF000000.toInt(), roles.surface)
    }

    @Test
    fun `dark and light palettes differ and light is lighter`() {
        val light = ColorRoles.fromSeed(0xFF1976D2.toInt(), dark = false)
        val dark = ColorRoles.fromSeed(0xFF1976D2.toInt(), dark = true)
        assertNotEquals(light.background, dark.background)
        assertTrue(luminance(light.background) > luminance(dark.background))
    }

    @Test
    fun `primary keeps the seed's hue family`() {
        val roles = ColorRoles.fromSeed(0xFFD32F2F.toInt(), dark = false)
        val r = (roles.primary shr 16) and 0xFF
        val g = (roles.primary shr 8) and 0xFF
        val b = roles.primary and 0xFF
        assertTrue(r > g && r > b)
    }

    @Test
    fun `different seeds produce different primaries`() {
        assertNotEquals(
            ColorRoles.fromSeed(0xFFD32F2F.toInt(), false).primary,
            ColorRoles.fromSeed(0xFF1976D2.toInt(), false).primary,
        )
    }

    @Test
    fun `all roles are opaque`() {
        val r = ColorRoles.fromSeed(0xFF9C27B0.toInt(), dark = true)
        assertEquals(0xFF, r.primary ushr 24)
        assertEquals(0xFF, r.surface ushr 24)
    }
}
