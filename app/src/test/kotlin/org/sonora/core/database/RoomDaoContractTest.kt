// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.runner.RunWith

/** Runs [DaoContract] against the real queries on an in-memory SQLite database. */
@RunWith(AndroidJUnit4::class)
class RoomDaoContractTest : DaoContract() {
    private val db: SonoraDatabase = Room
        .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), SonoraDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    override val podcasts get() = db.podcastDao()
    override val episodes get() = db.episodeDao()
    override val queue get() = db.syncQueueDao()
    override val syncState get() = db.syncStateDao()
    override val changes get() = db.subscriptionChangeDao()

    @After
    fun close() = db.close()
}
