// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.database

import androidx.room.RoomDatabase
import androidx.room.withTransaction

/** Seam over Room transactions so repositories can be unit-tested without a database. */
interface TransactionRunner {
    suspend fun <T> run(block: suspend () -> T): T
}

class RoomTransactionRunner(private val db: RoomDatabase) : TransactionRunner {
    override suspend fun <T> run(block: suspend () -> T): T = db.withTransaction { block() }
}
