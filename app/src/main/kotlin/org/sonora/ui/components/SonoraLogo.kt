// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.ui.components

import androidx.compose.foundation.Image
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import org.sonora.R

/** The Sonora wordmark, in whichever colourway reads against the current theme's background. */
@Composable
fun SonoraLogo(modifier: Modifier = Modifier) {
    val onDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Image(
        painter = painterResource(if (onDark) R.drawable.sonora_logo_on_dark else R.drawable.sonora_logo_on_light),
        contentDescription = "Sonora",
        contentScale = ContentScale.Fit,
        modifier = modifier,
    )
}
