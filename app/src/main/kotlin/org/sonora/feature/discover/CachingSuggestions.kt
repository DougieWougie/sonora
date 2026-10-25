// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.discover

import android.content.Context
import java.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.sonora.core.model.DirectoryPodcast

data class CachedSuggestions(val fetchedAtMillis: Long, val podcasts: List<DirectoryPodcast>)

interface SuggestionStore {
    fun load(): CachedSuggestions?
    fun save(suggestions: CachedSuggestions)
}

/**
 * Remembers the last answer so Discover opens with something to show, and only asks [source] again
 * once that answer is older than [ttlMillis]. A refresh that fails falls back to what is remembered.
 */
class CachingSuggestions(
    private val source: DirectorySuggestions,
    private val store: SuggestionStore,
    private val clock: Clock,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
) : DirectorySuggestions {
    private val lock = Mutex()
    private var memory: CachedSuggestions? = null

    private fun current() = memory ?: store.load()?.also { memory = it }

    override fun cached(): List<DirectoryPodcast> = current()?.podcasts.orEmpty()

    // Serialised so the start-up prefetch and the screen opening at the same moment share one fetch.
    override suspend fun suggestions(): List<DirectoryPodcast> = lock.withLock {
        val known = current()
        if (known != null && clock.millis() - known.fetchedAtMillis < ttlMillis) return known.podcasts
        try {
            val fresh = source.suggestions()
            if (fresh.isNotEmpty()) {
                CachedSuggestions(clock.millis(), fresh).also { memory = it; store.save(it) }
            }
            fresh
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            known?.podcasts ?: throw e
        }
    }

    companion object {
        const val DEFAULT_TTL_MILLIS = 6 * 60 * 60 * 1000L
    }
}

class PrefsSuggestionStore(context: Context) : SuggestionStore {
    private val prefs = context.applicationContext.getSharedPreferences("suggestions", Context.MODE_PRIVATE)

    @Serializable
    private data class Dto(val fetchedAtMillis: Long, val podcasts: List<PodcastDto>)

    @Serializable
    private data class PodcastDto(
        val title: String,
        val author: String?,
        val feedUrl: String,
        val artworkUrl: String?,
        val category: String? = null,
        val explicit: Boolean = false,
    )

    override fun load(): CachedSuggestions? {
        val text = prefs.getString(KEY, null) ?: return null
        return try {
            val dto = Json.decodeFromString<Dto>(text)
            CachedSuggestions(
                dto.fetchedAtMillis,
                dto.podcasts.map { DirectoryPodcast(it.title, it.author, it.feedUrl, it.artworkUrl, it.category, it.explicit) },
            )
        } catch (_: Exception) {
            null
        }
    }

    override fun save(suggestions: CachedSuggestions) {
        val dto = Dto(
            suggestions.fetchedAtMillis,
            suggestions.podcasts.map { PodcastDto(it.title, it.author, it.feedUrl, it.artworkUrl, it.category, it.explicit) },
        )
        prefs.edit().putString(KEY, Json.encodeToString(dto)).apply()
    }

    private companion object {
        const val KEY = "cache"
    }
}
