// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.download

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.sonora.core.database.FakeEpisodeDao
import org.sonora.core.database.FakePodcastDao
import org.sonora.core.model.DownloadState
import org.sonora.testutil.episodeEntity
import org.sonora.testutil.showId

class DownloadsMigrationTest {
    @TempDir lateinit var root: File

    private val podcasts = FakePodcastDao()
    private val episodes = FakeEpisodeDao()
    private val oldDir get() = File(root, "external/episodes")
    private val newDir get() = File(root, "internal/episodes")
    private fun migration(to: File = newDir) = DownloadsMigration(episodes, oldDir, to, Dispatchers.IO)

    private suspend fun downloaded(guid: String, bytes: ByteArray, dir: File = oldDir): Long {
        dir.mkdirs()
        val file = File(dir, "episode-$guid.mp3").apply { writeBytes(bytes) }
        episodes.insertAll(
            listOf(episodeEntity(podcasts.showId(), guid, downloadState = DownloadState.DOWNLOADED, downloadPath = file.absolutePath)),
        )
        return episodes.rows.value.first { it.guid == guid }.id
    }

    @Test
    fun `a completed download moves to internal storage and its path follows`() = runTest {
        val content = ByteArray(50_000) { (it % 251).toByte() }
        val id = downloaded("a", content)

        assertEquals(1, migration().run())

        val moved = File(newDir, "episode-a.mp3")
        assertArrayEquals(content, moved.readBytes())
        assertEquals(moved.absolutePath, episodes.findById(id)!!.downloadPath)
        assertFalse(File(oldDir, "episode-a.mp3").exists())
    }

    @Test
    fun `partial downloads and their validators move too, and the empty old folder is removed`() = runTest {
        oldDir.mkdirs()
        File(oldDir, "episode-9.mp3.part").writeText("partial")
        File(oldDir, "episode-9.mp3.part.validator").writeText("\"v1\"")

        migration().run()

        assertEquals("partial", File(newDir, "episode-9.mp3.part").readText())
        assertEquals("\"v1\"", File(newDir, "episode-9.mp3.part.validator").readText())
        assertFalse(oldDir.exists())
    }

    @Test
    fun `nothing to do without an old folder, and a second run changes nothing`() = runTest {
        assertEquals(0, migration().run())
        downloaded("a", ByteArray(10))
        assertEquals(1, migration().run())
        assertEquals(0, migration().run())
    }

    @Test
    fun `a file already copied before an interruption only needs its path fixed`() = runTest {
        val id = downloaded("a", ByteArray(10) { 7 })
        newDir.mkdirs()
        File(oldDir, "episode-a.mp3").copyTo(File(newDir, "episode-a.mp3"))

        migration().run()

        assertEquals(File(newDir, "episode-a.mp3").absolutePath, episodes.findById(id)!!.downloadPath)
        assertFalse(File(oldDir, "episode-a.mp3").exists())
    }

    @Test
    fun `when the copy fails the download stays where it was and still plays from there`() = runTest {
        val id = downloaded("a", ByteArray(10))
        val blocked = File(root, "blocked").apply { writeText("a file, not a folder") }

        assertEquals(0, migration(to = File(blocked, "episodes")).run())

        val old = File(oldDir, "episode-a.mp3")
        assertTrue(old.isFile)
        assertEquals(old.absolutePath, episodes.findById(id)!!.downloadPath)
    }

    @Test
    fun `downloads outside the old folder are left alone`() = runTest {
        val elsewhere = File(root, "elsewhere")
        val id = downloaded("b", ByteArray(5), dir = elsewhere)

        migration().run()

        assertTrue(File(elsewhere, "episode-b.mp3").isFile)
        assertEquals(File(elsewhere, "episode-b.mp3").absolutePath, episodes.findById(id)!!.downloadPath)
    }
}
