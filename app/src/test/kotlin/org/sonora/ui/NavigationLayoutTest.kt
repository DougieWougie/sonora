// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.ui

import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NavigationLayoutTest {
    @Test
    fun `a phone in portrait uses the bottom bar`() {
        assertEquals(NavigationLayout.BottomBar, navigationLayoutFor(width = 411.dp, height = 891.dp))
    }

    @Test
    fun `a phone in landscape keeps the bottom bar but makes it slim`() {
        assertEquals(NavigationLayout.SlimBottomBar, navigationLayoutFor(width = 891.dp, height = 411.dp))
    }

    @Test
    fun `a tablet in either orientation uses the rail`() {
        assertEquals(NavigationLayout.Rail, navigationLayoutFor(width = 1280.dp, height = 800.dp))
        assertEquals(NavigationLayout.Rail, navigationLayoutFor(width = 800.dp, height = 1280.dp))
    }

    @Test
    fun `a tall narrow window below 600dp wide never uses the rail`() {
        assertEquals(NavigationLayout.BottomBar, navigationLayoutFor(width = 599.dp, height = 900.dp))
    }
}
