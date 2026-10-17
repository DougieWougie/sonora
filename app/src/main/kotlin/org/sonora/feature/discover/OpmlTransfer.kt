// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.discover

import java.io.InputStream
import java.io.OutputStream
import org.sonora.core.database.PodcastDao
import org.sonora.core.database.SubscriptionRepository
import org.sonora.core.model.OpmlEntry
import org.sonora.core.network.isTransportAllowed
import org.sonora.core.parser.OpmlParser
import org.sonora.core.parser.OpmlWriter

data class OpmlImportResult(val imported: Int, val alreadySubscribed: Int, val rejected: Int = 0)

interface OpmlTransfer {
    suspend fun import(input: InputStream): OpmlImportResult

    /** Writes every subscription; returns how many. */
    suspend fun export(output: OutputStream): Int
}

class DefaultOpmlTransfer(
    private val parser: OpmlParser,
    private val writer: OpmlWriter,
    private val podcastDao: PodcastDao,
    private val subscriptions: SubscriptionRepository,
    private val allowCleartext: () -> Boolean = { false },
) : OpmlTransfer {
    override suspend fun import(input: InputStream): OpmlImportResult {
        val entries = input.use(parser::parse) // parse everything first: a bad file subscribes to nothing
        var imported = 0
        var existing = 0
        var rejected = 0
        for (entry in entries.distinctBy { it.feedUrl }) {
            when {
                !isTransportAllowed(entry.feedUrl, allowCleartext()) -> rejected++
                podcastDao.findByFeedUrl(entry.feedUrl) != null -> existing++
                else -> {
                    subscriptions.subscribe(entry.feedUrl, entry.title)
                    imported++
                }
            }
        }
        return OpmlImportResult(imported, existing, rejected)
    }

    override suspend fun export(output: OutputStream): Int {
        val podcasts = podcastDao.getAll().sortedBy { it.title.lowercase() }
        writer.write(podcasts.map { OpmlEntry(it.title, it.feedUrl, it.link) }, "Sonora subscriptions", output)
        return podcasts.size
    }
}
