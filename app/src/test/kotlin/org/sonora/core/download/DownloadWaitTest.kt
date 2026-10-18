// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.download

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DownloadWaitTest {
    private val anywhere = DownloadRequirements(unmetered = false, charging = false)
    private val wifiOnly = DownloadRequirements(unmetered = true, charging = false)
    private val chargingOnly = DownloadRequirements(unmetered = false, charging = true)

    private val offline = DeviceConditions(connected = false, unmetered = false, charging = false)
    private val mobile = DeviceConditions(connected = true, unmetered = false, charging = false)
    private val wifi = DeviceConditions(connected = true, unmetered = true, charging = false)
    private val wifiCharging = DeviceConditions(connected = true, unmetered = true, charging = true)

    @Test
    fun `waits for a connection when offline`() {
        assertEquals(WaitReason.CONNECTION, waitReason(anywhere, offline))
    }

    @Test
    fun `offline takes priority over every other reason`() {
        assertEquals(WaitReason.CONNECTION, waitReason(DownloadRequirements(unmetered = true, charging = true), offline))
    }

    @Test
    fun `waits for wifi when the download is wifi-only and the connection is metered`() {
        assertEquals(WaitReason.WIFI, waitReason(wifiOnly, mobile))
    }

    @Test
    fun `waits for the charger when it requires charging`() {
        assertEquals(WaitReason.CHARGER, waitReason(chargingOnly, wifi))
    }

    @Test
    fun `wifi is reported before the charger when both are missing`() {
        assertEquals(WaitReason.WIFI, waitReason(DownloadRequirements(unmetered = true, charging = true), mobile))
    }

    @Test
    fun `has no reason to wait when every requirement is met`() {
        assertNull(waitReason(anywhere, mobile))
        assertNull(waitReason(DownloadRequirements(unmetered = true, charging = true), wifiCharging))
    }

    @Test
    fun `reasons are worked out per episode and skip those with nothing missing`() {
        val reasons = waitReasons(
            mapOf(1L to wifiOnly, 2L to anywhere, 3L to chargingOnly),
            mobile,
        )

        assertEquals(mapOf(1L to WaitReason.WIFI, 3L to WaitReason.CHARGER), reasons)
    }
}
