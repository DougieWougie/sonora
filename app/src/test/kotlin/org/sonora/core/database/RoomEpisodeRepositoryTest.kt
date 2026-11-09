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

        // The user marks the episode played at the worst moment: right after the repository's read (the old
        // read-then-@Update code) or right before its write (the targeted UPDATE, which does not read at all).
        var interleaved = 0
        suspend fun markPlayed() {
            interleaved++
            real.update(real.findById(id)!!.copy(played = true, playbackPositionSeconds = 300))
        }
        val racing = object : EpisodeDao by real {
            override suspend fun findById(id: Long): EpisodeEntity? = real.findById(id).also { markPlayed() }
            override suspend fun updateDownload(id: Long, state: DownloadState, bytes: Long, keepPath: Boolean): Int {
                markPlayed()
                return real.updateDownload(id, state, bytes, keepPath)
            }
        }
        val repo = EpisodeRepository(db.podcastDao(), racing, db.syncQueueDao(), FakeTransactionRunner(), MutableClock())

        repo.updateDownload(id, DownloadState.DOWNLOADING, 1024)

        val row = real.findById(id)!!
        assertTrue("the concurrent write never happened, so this test proves nothing", interleaved > 0)
        assertTrue(row.played)
        assertEquals(300L, row.playbackPositionSeconds)
        assertEquals(DownloadState.DOWNLOADING, row.downloadState)
        assertEquals(1024L, row.downloadedBytes)
    }
}
