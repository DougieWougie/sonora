// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.player

/** "Title — Show", with the episode's (or failing that the show's) web link on its own line when [includeLink]. */
fun shareText(episode: PlayerEpisode, includeLink: Boolean): String {
    val heading = listOfNotNull(episode.title, episode.podcastTitle).joinToString(" — ")
    val link = episode.link?.takeIf { includeLink }
    return listOfNotNull(heading, link).joinToString("\n")
}
