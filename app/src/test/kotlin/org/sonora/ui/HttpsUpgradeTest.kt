// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HttpsUpgradeTest {
    @Test
    fun `http becomes https`() {
        assertEquals("https://cdn.example.com/a.png?x=1", upgradedToHttps("http://cdn.example.com/a.png?x=1"))
    }

    @Test
    fun `the scheme is matched case-insensitively`() {
        assertEquals("https://cdn.example.com/a.png", upgradedToHttps("HTTP://cdn.example.com/a.png"))
    }

    @Test
    fun `https and other schemes need no upgrade`() {
        assertNull(upgradedToHttps("https://cdn.example.com/a.png"))
        assertNull(upgradedToHttps("file:///sdcard/a.png"))
        assertNull(upgradedToHttps("content://media/1"))
    }

    @Test
    fun `a host that merely starts with http is untouched`() {
        assertNull(upgradedToHttps("httpbin.org/image"))
    }
}
