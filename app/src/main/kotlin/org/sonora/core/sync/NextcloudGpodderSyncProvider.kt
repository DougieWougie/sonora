// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.sync

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import org.sonora.core.model.SyncActionType
import org.sonora.core.network.EpisodeActionDto
import org.sonora.core.network.GpodderSyncApi
import org.sonora.core.network.SubscriptionPushDto
import retrofit2.HttpException

/** [SyncProvider] for the Nextcloud `gpoddersync` app. */
class NextcloudGpodderSyncProvider(private val api: GpodderSyncApi) : SyncProvider {

    override suspend fun pullSubscriptions(since: Long): SubscriptionChanges = call {
        val dto = api.subscriptions(since)
        SubscriptionChanges(dto.add, dto.remove, dto.timestamp)
    }

    override suspend fun pushSubscriptions(add: List<String>, remove: List<String>): Long = call {
        api.pushSubscriptions(SubscriptionPushDto(add, remove)).timestamp
    }

    override suspend fun pullEpisodeActions(since: Long): EpisodeActionsPage = call {
        val dto = api.episodeActions(since)
        EpisodeActionsPage(dto.actions.mapNotNull { it.toDomain() }, dto.timestamp)
    }

    override suspend fun pushEpisodeActions(actions: List<RemoteEpisodeAction>): Long = call {
        api.pushEpisodeActions(actions.map { it.toDto() }).timestamp
    }

    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: HttpException) {
        when (e.code()) {
            401, 403 -> throw SyncAuthException("Sync server rejected the credentials (HTTP ${e.code()})", e)
            else -> throw SyncException("Sync server returned HTTP ${e.code()}", e)
        }
    }

    /** Actions this app does not model (e.g. `flattr`) and unparseable timestamps are dropped. */
    private fun EpisodeActionDto.toDomain(): RemoteEpisodeAction? {
        val type = SyncActionType.entries.firstOrNull { it.name.equals(action, ignoreCase = true) } ?: return null
        val millis = parseTimestamp(timestamp) ?: return null
        return RemoteEpisodeAction(
            feedUrl = podcast,
            episodeUrl = episode,
            guid = guid,
            type = type,
            timestampUtcMillis = millis,
            startedSeconds = started?.takeIf { it >= 0 },
            positionSeconds = position?.takeIf { it >= 0 },
            totalSeconds = total?.takeIf { it >= 0 },
        )
    }

    private fun RemoteEpisodeAction.toDto() = EpisodeActionDto(
        podcast = feedUrl,
        episode = episodeUrl,
        guid = guid,
        action = type.name,
        timestamp = formatTimestamp(timestampUtcMillis),
        started = startedSeconds,
        position = positionSeconds,
        total = totalSeconds,
    )

    private companion object {
        val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

        fun formatTimestamp(millis: Long): String =
            FORMAT.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC))

        /** The server sends zone-less ISO timestamps in UTC; tolerate an explicit offset or `Z` too. */
        fun parseTimestamp(text: String): Long? = try {
            LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli()
        } catch (_: DateTimeParseException) {
            try {
                Instant.parse(text).toEpochMilli()
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }
}
