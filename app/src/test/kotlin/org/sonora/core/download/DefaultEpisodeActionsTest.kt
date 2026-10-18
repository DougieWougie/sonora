// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.download

import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.sonora.core.database.EpisodeRepository
import org.sonora.core.sync.DownloadScheduler

class DefaultEpisodeActionsTest {
    private val manager = mockk<EpisodeDownloadManager>(relaxed = true)
    private val repository = mockk<EpisodeRepository>(relaxed = true)
    private val scheduled = mutableListOf<Pair<List<Long>, Boolean>>()
    private val scheduler = DownloadScheduler { ids, unmetered, _ -> scheduled += ids to unmetered }
    private val actions = DefaultEpisodeActions(manager, repository, scheduler)

    @Test
    fun `an explicit download does not wait for wifi`() = runTest {
        actions.download(7)
        assertEquals(listOf(listOf(7L) to false), scheduled)
    }

    @Test
    fun `deleting a download goes through the manager`() = runTest {
        actions.deleteDownload(3)
        coVerify { manager.deleteDownload(3) }
    }

    @Test
    fun `marking played goes through the manager so the download policy applies`() = runTest {
        actions.markPlayed(4)
        coVerify { manager.markPlayed(4) }
    }

    @Test
    fun `marking unplayed goes through the repository`() = runTest {
        actions.markUnplayed(5)
        coVerify { repository.markNew(5) }
    }
}
