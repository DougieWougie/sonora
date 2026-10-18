// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.ui.components

import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.sonora.core.model.DownloadState
import org.sonora.feature.common.EpisodeItem

class SwipeActionColoursTest {
    private val scheme = lightColorScheme()
    private val item = EpisodeItem(
        id = 7, podcastId = 1, podcastTitle = "Show", title = "Episode seven", publishedAtMillis = 700,
        durationSeconds = 600, positionSeconds = 0, played = false, downloadState = DownloadState.NONE,
        downloadedBytes = 0, sizeBytes = null, artworkUrl = null, queued = false,
    )

    @Test
    fun `queueing swipes onto the primary container`() {
        val style = swipeActionStyle(SwipeToDismissBoxValue.StartToEnd, item, scheme)
        assertEquals(scheme.primaryContainer, style.container)
        assertEquals(scheme.onPrimaryContainer, style.content)
    }

    @Test
    fun `downloading swipes onto the tertiary container`() {
        val style = swipeActionStyle(SwipeToDismissBoxValue.EndToStart, item, scheme)
        assertEquals(scheme.tertiaryContainer, style.container)
        assertEquals(scheme.onTertiaryContainer, style.content)
    }

    @Test
    fun `deleting a download is shown in the error colours`() {
        val downloaded = item.copy(downloadState = DownloadState.DOWNLOADED)
        val style = swipeActionStyle(SwipeToDismissBoxValue.EndToStart, downloaded, scheme)
        assertEquals(scheme.errorContainer, style.container)
        assertEquals(scheme.onErrorContainer, style.content)
    }

    @Test
    fun `every action stands out from the row surface`() {
        listOf(SwipeToDismissBoxValue.StartToEnd, SwipeToDismissBoxValue.EndToStart).forEach {
            assertNotEquals(scheme.surface, swipeActionStyle(it, item, scheme).container)
        }
    }

    @Test
    fun `nothing is painted before a swipe starts`() {
        assertEquals(Color.Transparent, swipeActionStyle(SwipeToDismissBoxValue.Settled, item, scheme).container)
    }
}
