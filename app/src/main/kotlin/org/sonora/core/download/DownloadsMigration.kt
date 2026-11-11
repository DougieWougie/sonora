// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.download

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.sonora.core.database.EpisodeDao
import org.sonora.core.model.DownloadState

/**
 * Moves downloads that older versions kept in shared external app storage into [newDir] (internal storage,
 * which other apps and the user's file manager cannot reach).
 *
 * Each file is copied first, the episode's path is repointed, and only then is the original deleted, so a stored
 * path always names a file that exists. A file that cannot be copied (no space, say) stays where it was, keeps
 * playing from there, and is tried again at the next start. Nothing is touched in Room beyond `downloadPath`.
 */
class DownloadsMigration(
    private val episodeDao: EpisodeDao,
    private val oldDir: File?,
    private val newDir: File,
    private val ioDispatcher: CoroutineDispatcher,
) {
    /** Returns how many completed downloads were moved. */
    suspend fun run(): Int = withContext(ioDispatcher) {
        val old = oldDir?.takeIf { it.isDirectory && it.absoluteFile != newDir.absoluteFile } ?: return@withContext 0
        var moved = 0
        for (episode in episodeDao.getByDownloadState(DownloadState.DOWNLOADED)) {
            val path = episode.downloadPath ?: continue
            val source = File(path)
            if (source.parentFile?.absoluteFile != old.absoluteFile) continue
            val target = File(newDir, source.name)
            try {
                if (!target.isFile) copy(source, target)
                episodeDao.updateDownloadPath(episode.id, target.absolutePath)
                source.delete()
                moved++
            } catch (_: IOException) {
                target.delete()
            }
        }
        // Partial downloads and their validators carry no database state, so they simply move.
        old.listFiles()?.filter { it.isFile }?.forEach { leftover ->
            val target = File(newDir, leftover.name)
            try {
                if (!target.isFile) copy(leftover, target)
                leftover.delete()
            } catch (_: IOException) {
                target.delete()
            }
        }
        old.delete() // only succeeds when empty
        moved
    }

    /** Copies through a temporary name so an interrupted copy is never mistaken for a finished file. */
    private fun copy(source: File, target: File) {
        if (!newDir.isDirectory && !newDir.mkdirs()) throw IOException("Cannot create $newDir")
        val partial = File(newDir, "${target.name}.migrating")
        source.copyTo(partial, overwrite = true)
        if (partial.length() != source.length() || !partial.renameTo(target)) {
            partial.delete()
            throw IOException("Could not copy ${source.name}")
        }
    }
}
