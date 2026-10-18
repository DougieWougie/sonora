// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.ui.theme

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight

/** Used when there is neither artwork nor system dynamic colour. */
const val DEFAULT_SEED: Int = 0xFF3F6FB5.toInt()

fun ColorRoles.toColorScheme(dark: Boolean): ColorScheme {
    fun c(argb: Int) = Color(argb)
    return (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = c(primary), onPrimary = c(onPrimary),
        primaryContainer = c(primaryContainer), onPrimaryContainer = c(onPrimaryContainer),
        inversePrimary = c(primaryContainer),
        secondary = c(secondary), onSecondary = c(onSecondary),
        secondaryContainer = c(secondaryContainer), onSecondaryContainer = c(onSecondaryContainer),
        tertiary = c(tertiary), onTertiary = c(onTertiary),
        tertiaryContainer = c(tertiaryContainer), onTertiaryContainer = c(onTertiaryContainer),
        background = c(background), onBackground = c(onBackground),
        surface = c(surface), onSurface = c(onSurface),
        surfaceVariant = c(surfaceVariant), onSurfaceVariant = c(onSurfaceVariant),
        surfaceTint = c(primary), inverseSurface = c(onSurface), inverseOnSurface = c(surface),
        outline = c(outline), outlineVariant = c(surfaceVariant),
        surfaceBright = c(surfaceContainer), surfaceDim = c(surface),
        surfaceContainerLowest = c(surface), surfaceContainerLow = c(surfaceContainer),
        surfaceContainer = c(surfaceContainer), surfaceContainerHigh = c(surfaceContainer),
        surfaceContainerHighest = c(surfaceVariant),
    )
}

/** Seed palette → system dynamic colour (Android 12+) → static seed palette, in that order of preference. */
@Composable
private fun targetColorScheme(state: ThemeState, dark: Boolean): ColorScheme {
    val context = LocalContext.current
    val seed = state.effectiveSeed
    val amoled = state.amoled && dark
    return when {
        seed != null -> remember(seed, dark, amoled) { ColorRoles.fromSeed(seed, dark, amoled).toColorScheme(dark) }
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val scheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            if (amoled) scheme.copy(background = Color.Black, surface = Color.Black) else scheme
        }
        else -> remember(dark, amoled) { ColorRoles.fromSeed(DEFAULT_SEED, dark, amoled).toColorScheme(dark) }
    }
}

/** Cross-fades between palettes with the expressive effects spring (colour changes don't move anything). */
@Composable
fun animatedColorScheme(target: ColorScheme, motionScheme: MotionScheme): ColorScheme {
    val spec = motionScheme.defaultEffectsSpec<Color>()
    @Composable fun a(c: Color) = animateColorAsState(c, spec, label = "scheme").value
    return target.copy(
        primary = a(target.primary), onPrimary = a(target.onPrimary),
        primaryContainer = a(target.primaryContainer), onPrimaryContainer = a(target.onPrimaryContainer),
        secondary = a(target.secondary), onSecondary = a(target.onSecondary),
        secondaryContainer = a(target.secondaryContainer), onSecondaryContainer = a(target.onSecondaryContainer),
        tertiary = a(target.tertiary), onTertiary = a(target.onTertiary),
        tertiaryContainer = a(target.tertiaryContainer), onTertiaryContainer = a(target.onTertiaryContainer),
        background = a(target.background), onBackground = a(target.onBackground),
        surface = a(target.surface), onSurface = a(target.onSurface),
        surfaceVariant = a(target.surfaceVariant), onSurfaceVariant = a(target.onSurfaceVariant),
        surfaceContainer = a(target.surfaceContainer), surfaceContainerLow = a(target.surfaceContainerLow),
        surfaceContainerHigh = a(target.surfaceContainerHigh), outline = a(target.outline),
    )
}

/** Tabular figures so a ticking timestamp doesn't jitter. */
val TabularNumbers = "tnum"

val SonoraTypography: Typography = Typography().let { base ->
    base.copy(
        headlineLargeEmphasized = base.headlineLargeEmphasized.copy(fontWeight = FontWeight.Bold),
        titleMediumEmphasized = base.titleMediumEmphasized.copy(fontWeight = FontWeight.SemiBold),
        labelLargeEmphasized = base.labelLargeEmphasized.copy(fontFeatureSettings = TabularNumbers),
    )
}

fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = TabularNumbers)

@Composable
fun PodcastExpressiveTheme(state: ThemeState = ThemeState(), content: @Composable () -> Unit) {
    val dark = state.isDark(isSystemInDarkTheme())
    val motion = remember { MotionScheme.expressive() }
    MaterialExpressiveTheme(
        colorScheme = animatedColorScheme(targetColorScheme(state, dark), motion),
        motionScheme = motion,
        typography = SonoraTypography,
        content = content,
    )
}

@Composable
fun <T> fastSpatialSpec() = MaterialTheme.motionScheme.fastSpatialSpec<T>()
