// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.model

data class ParsedFeed(
    val title: String,
    val description: String?,
    val link: String?,
    val author: String?,
    val imageUrl: String?,
    val episodes: List<ParsedEpisode>,
)

data class ParsedEpisode(
    val guid: String,
    val title: String,
    val description: String?,
    val enclosureUrl: String,
    val mimeType: String?,
    val sizeBytes: Long?,
    val durationSeconds: Long?,
    val publishedAtMillis: Long?,
    val imageUrl: String?,
    /** The episode's web page, when the feed gives one. */
    val link: String? = null,
)

data class OpmlEntry(
    val title: String,
    val feedUrl: String,
    val htmlUrl: String?,
)
