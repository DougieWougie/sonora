// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SmokeTest {
    @Test
    fun `build toolchain runs unit tests`() {
        assertEquals(4, 2 + 2)
    }
}
