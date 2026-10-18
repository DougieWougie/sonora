// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.work

import android.content.Context
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import org.sonora.core.download.DownloadRequirements
import org.sonora.core.download.DownloadWaits
import org.sonora.core.download.WaitReason
import org.sonora.core.download.waitReasons

/**
 * Compares what each enqueued download job requires (read back from its WorkManager constraints)
 * with the device's current state.
 */
class WorkManagerDownloadWaits(context: Context, private val device: DeviceConditionsMonitor) : DownloadWaits {
    private val workManager = WorkManager.getInstance(context)

    override fun observe(): Flow<Map<Long, WaitReason>> =
        combine(requirements(), device.observe()) { required, conditions -> waitReasons(required, conditions) }

    private fun requirements(): Flow<Map<Long, DownloadRequirements>> =
        workManager.getWorkInfosByTagFlow(WorkManagerDownloadScheduler.TAG_DOWNLOAD).map { infos ->
            buildMap {
                for (info in infos) {
                    if (info.state != WorkInfo.State.ENQUEUED) continue
                    val episodeId = info.tags.firstNotNullOfOrNull(WorkManagerDownloadScheduler::episodeIdFromTag) ?: continue
                    put(
                        episodeId,
                        DownloadRequirements(
                            unmetered = info.constraints.requiredNetworkType == NetworkType.UNMETERED,
                            charging = info.constraints.requiresCharging(),
                        ),
                    )
                }
            }
        }
}
