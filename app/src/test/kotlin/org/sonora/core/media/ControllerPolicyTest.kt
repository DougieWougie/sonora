// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.media

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ControllerPolicyTest {
    @Test
    fun `system controllers and our own app may connect`() {
        assertTrue(isAllowedController(isTrusted = true, controllerPackage = "com.android.systemui", ownPackage = "org.sonora"))
        assertTrue(isAllowedController(isTrusted = false, controllerPackage = "org.sonora", ownPackage = "org.sonora"))
    }

    @Test
    fun `an untrusted third-party app is refused`() {
        assertFalse(isAllowedController(isTrusted = false, controllerPackage = "com.example.sniffer", ownPackage = "org.sonora"))
    }

    @Test
    fun `a package that only looks like ours is refused`() {
        assertFalse(isAllowedController(isTrusted = false, controllerPackage = "org.sonora.evil", ownPackage = "org.sonora"))
    }
}
