// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.sonora.core.model.DownloadState
import org.sonora.testutil.episodeEntity

/** Download progress runs concurrently with playback, so it must not overwrite what playback just wrote. */
@RunWith(AndroidJUnit4::class)
class RoomEpisodeRepositoryTest {
    private val db: SonoraDatabase = Room
        .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), SonoraDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    @After
    fun close() = db.close()

    @Test
    fun `a played mark landing during a download update survives it`() = runBlocking {
        val real = db.episodeDao()
        val podcastId = db.podcastDao().insert(PodcastEntity(feedUrl = "https://f", title = "F"))
        real.insertAll(listOf(episodeEntity(podcastId, "g")))
        val id = real.getForPodcast(podcastId).single().id

        // Whatever the repository reads, the user marks the episode played right after the read.
        val racing = object : EpisodeDao by real {
            override suspend fun findById(id: Long): EpisodeEntity? =
                real.findById(id).also { row -> real.update(row!!.copy(played = true, playbackPositionSeconds = 300)) }
        }
        val repo = EpisodeRepository(db.podcastDao(), racing, db.syncQueueDao(), FakeTransactionRunner(), MutableClock())

        repo.updateDownload(id, DownloadState.DOWNLOADING, 1024)

        val row = real.findById(id)!!
        assertTrue(row.played)
        assertEquals(300L, row.playbackPositionSeconds)
        assertEquals(DownloadState.DOWNLOADING, row.downloadState)
        assertEquals(1024L, row.downloadedBytes)
    }
}
