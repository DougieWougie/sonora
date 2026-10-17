// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.discover

import kotlinx.coroutines.flow.Flow
import org.sonora.core.model.DirectoryPodcast

fun interface DirectorySearch {
    fun search(query: String, source: DirectorySource): Flow<List<DirectoryPodcast>>
}
