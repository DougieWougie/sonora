// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies Room [AutoMigration]s against the exported schema JSON files using real SQLite
 * databases, without requiring an extra `room-testing` Maven artifact.
 */
@RunWith(AndroidJUnit4::class)
class SonoraDatabaseMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val createdDbs = mutableListOf<String>()

    @After
    fun tearDown() {
        createdDbs.forEach { context.deleteDatabase(it) }
    }

    @Test
    fun `migrates from schema v1 to current preserving podcasts and episodes`() = runBlocking {
        val dbName = createDatabaseFromSchema(1) { db ->
            db.execSQL(
                """
                INSERT INTO podcast (id, feedUrl, title, description, link, author, imageUrl, lastRefreshedAtMillis, autoDownload)
                VALUES (1, 'https://example.com/feed.xml', 'Show', 'Desc', 'https://example.com', 'Host', 'https://example.com/art.jpg', 1000, 1)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO episode (id, podcastId, guid, title, description, enclosureUrl, mimeType, sizeBytes, durationSeconds, publishedAtMillis, imageUrl, playbackPositionSeconds, played, downloadState, downloadPath, downloadedBytes)
                VALUES (10, 1, 'g1', 'Ep 1', 'Notes', 'https://example.com/ep1.mp3', 'audio/mpeg', 1234, 600, 2000, NULL, 45, 0, 'NONE', NULL, 0)
                """.trimIndent(),
            )
        }

        val migrated = Room.databaseBuilder(context, SonoraDatabase::class.java, dbName)
            .allowMainThreadQueries()
            .build()
        try {
            val podcast = migrated.podcastDao().findById(1L)!!
            assertEquals("Show", podcast.title)
            assertEquals("https://example.com/feed.xml", podcast.feedUrl)
            assertFalse(podcast.notifyNewEpisodes)

            val episode = migrated.episodeDao().findById(10L)!!
            assertEquals("Ep 1", episode.title)
            assertEquals(45, episode.playbackPositionSeconds)
            assertNull(episode.link)
        } finally {
            migrated.close()
        }
    }

    @Test
    fun `migrates from schema v2 to current preserving notifyNewEpisodes`() = runBlocking {
        val dbName = createDatabaseFromSchema(2) { db ->
            db.execSQL(
                """
                INSERT INTO podcast (id, feedUrl, title, description, link, author, imageUrl, lastRefreshedAtMillis, autoDownload, notifyNewEpisodes)
                VALUES (2, 'https://example.com/feed2.xml', 'Show 2', NULL, NULL, NULL, NULL, 3000, 0, 1)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO episode (id, podcastId, guid, title, description, enclosureUrl, mimeType, sizeBytes, durationSeconds, publishedAtMillis, imageUrl, playbackPositionSeconds, played, downloadState, downloadPath, downloadedBytes)
                VALUES (20, 2, 'g2', 'Ep 2', NULL, 'https://example.com/ep2.mp3', NULL, NULL, 900, 4000, NULL, 0, 1, 'NONE', NULL, 0)
                """.trimIndent(),
            )
        }

        val migrated = Room.databaseBuilder(context, SonoraDatabase::class.java, dbName)
            .allowMainThreadQueries()
            .build()
        try {
            val podcast = migrated.podcastDao().findById(2L)!!
            assertEquals("Show 2", podcast.title)
            assertEquals(true, podcast.notifyNewEpisodes)

            val episode = migrated.episodeDao().findById(20L)!!
            assertEquals("Ep 2", episode.title)
            assertEquals(true, episode.played)
            assertNull(episode.link)
        } finally {
            migrated.close()
        }
    }

    @Test
    fun `migrates from schema v3 to current preserving episode link and defaulting validators to null`() = runBlocking {
        val dbName = createDatabaseFromSchema(3) { db ->
            db.execSQL(
                """
                INSERT INTO podcast (id, feedUrl, title, description, link, author, imageUrl, lastRefreshedAtMillis, autoDownload, notifyNewEpisodes)
                VALUES (3, 'https://example.com/feed3.xml', 'Show 3', NULL, NULL, NULL, NULL, 5000, 1, 1)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO episode (id, podcastId, guid, title, description, enclosureUrl, mimeType, sizeBytes, durationSeconds, publishedAtMillis, imageUrl, playbackPositionSeconds, played, downloadState, downloadPath, downloadedBytes, link)
                VALUES (30, 3, 'g3', 'Ep 3', NULL, 'https://example.com/ep3.mp3', NULL, NULL, 900, 6000, NULL, 10, 0, 'NONE', NULL, 0, 'https://example.com/ep3')
                """.trimIndent(),
            )
        }

        val migrated = Room.databaseBuilder(context, SonoraDatabase::class.java, dbName)
            .allowMainThreadQueries()
            .build()
        try {
            val podcast = migrated.podcastDao().findById(3L)!!
            assertEquals("Show 3", podcast.title)
            assertNull(podcast.etag)
            assertNull(podcast.lastModified)

            val episode = migrated.episodeDao().findById(30L)!!
            assertEquals("https://example.com/ep3", episode.link)
        } finally {
            migrated.close()
        }
    }

    private fun createDatabaseFromSchema(version: Int, seed: (SQLiteDatabase) -> Unit): String {
        val name = "migration-v$version-${System.nanoTime()}.db"
        createdDbs += name
        val schemaFile = File("schemas/org.sonora.core.database.SonoraDatabase/$version.json")
        val root = Json.parseToJsonElement(schemaFile.readText()).jsonObject["database"]!!.jsonObject
        val dbFile = context.getDatabasePath(name)
        dbFile.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            root["entities"]!!.jsonArray.forEach { entityElement ->
                val entity = entityElement.jsonObject
                val table = entity["tableName"]!!.jsonPrimitive.content
                val createSql = entity["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)
                db.execSQL(createSql)
                entity["indices"]?.jsonArray?.forEach { indexElement ->
                    val indexSql = indexElement.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)
                    db.execSQL(indexSql)
                }
            }
            root["setupQueries"]?.jsonArray?.forEach { query ->
                db.execSQL(query.jsonPrimitive.content)
            }
            seed(db)
            db.version = version
        } finally {
            db.close()
        }
        return name
    }
}
