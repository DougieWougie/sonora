// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.download

import kotlinx.coroutines.flow.Flow

/** What a queued download needs beyond some kind of connection. */
data class DownloadRequirements(val unmetered: Boolean, val charging: Boolean)

data class DeviceConditions(val connected: Boolean, val unmetered: Boolean, val charging: Boolean)

enum class WaitReason { CONNECTION, WIFI, CHARGER }

/** Why each queued episode is not running yet; episodes with nothing missing are absent. */
fun interface DownloadWaits {
    fun observe(): Flow<Map<Long, WaitReason>>
}

fun waitReason(requirements: DownloadRequirements, device: DeviceConditions): WaitReason? = when {
    !device.connected -> WaitReason.CONNECTION
    requirements.unmetered && !device.unmetered -> WaitReason.WIFI
    requirements.charging && !device.charging -> WaitReason.CHARGER
    else -> null
}

fun waitReasons(requirements: Map<Long, DownloadRequirements>, device: DeviceConditions): Map<Long, WaitReason> =
    requirements.mapNotNull { (id, required) -> waitReason(required, device)?.let { id to it } }.toMap()
