// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.sync

import org.sonora.core.model.SyncActionType

data class SubscriptionChanges(val add: List<String>, val remove: List<String>, val timestamp: Long)

/** An episode action as exchanged with a server. [timestampUtcMillis] is UTC; absent numbers are null. */
data class RemoteEpisodeAction(
    val feedUrl: String,
    val episodeUrl: String,
    val guid: String?,
    val type: SyncActionType,
    val timestampUtcMillis: Long,
    val startedSeconds: Long? = null,
    val positionSeconds: Long? = null,
    val totalSeconds: Long? = null,
)

data class EpisodeActionsPage(val actions: List<RemoteEpisodeAction>, val timestamp: Long)

open class SyncException(message: String, cause: Throwable? = null) : Exception(message, cause)

class SyncAuthException(message: String, cause: Throwable? = null) : SyncException(message, cause)

/**
 * A gpodder-style sync backend. Cursors ([since] / returned timestamps) are the server's own
 * UNIX-second clock, never the device clock.
 */
interface SyncProvider {
    suspend fun pullSubscriptions(since: Long): SubscriptionChanges

    /** Returns the server timestamp of the change. */
    suspend fun pushSubscriptions(add: List<String>, remove: List<String>): Long

    suspend fun pullEpisodeActions(since: Long): EpisodeActionsPage

    /** Returns the server timestamp of the change. */
    suspend fun pushEpisodeActions(actions: List<RemoteEpisodeAction>): Long
}
