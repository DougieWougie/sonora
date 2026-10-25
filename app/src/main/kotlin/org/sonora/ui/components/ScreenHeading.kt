// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Names a screen to screen readers without spending any screen space; the nav bar already shows it visually. */
@Composable
fun ScreenHeading(title: String, modifier: Modifier = Modifier) {
    Spacer(modifier.size(0.dp).semantics { heading(); contentDescription = title })
}
