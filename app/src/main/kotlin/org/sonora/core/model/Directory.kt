// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.model

/** A podcast found in a public directory, not yet subscribed to. */
data class DirectoryPodcast(
    val title: String,
    val author: String?,
    val feedUrl: String,
    val artworkUrl: String?,
)
