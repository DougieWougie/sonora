// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.ui.theme

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A colour found in artwork and how many pixels it stands for. */
data class ColorSample(val argb: Int, val population: Int)

object SeedColorSelector {
    private const val MIN_SATURATION = 0.25f
    private const val MIN_LIGHTNESS = 0.15f
    private const val MAX_LIGHTNESS = 0.85f

    /** The most prominent vivid colour; if the artwork has none, the most common colour. Always opaque. */
    fun pick(samples: List<ColorSample>): ColorSample? {
        val vivid = samples.filter {
            val hsl = Hsl.of(it.argb)
            hsl.s >= MIN_SATURATION && hsl.l in MIN_LIGHTNESS..MAX_LIGHTNESS
        }
        val best = if (vivid.isNotEmpty()) {
            vivid.maxBy { it.population * Hsl.of(it.argb).s }
        } else {
            samples.maxByOrNull { it.population }
        }
        return best?.copy(argb = best.argb or OPAQUE)
    }
}

private const val OPAQUE = 0xFF000000.toInt()

internal data class Hsl(val h: Float, val s: Float, val l: Float) {
    companion object {
        fun of(argb: Int): Hsl {
            val r = ((argb shr 16) and 0xFF) / 255f
            val g = ((argb shr 8) and 0xFF) / 255f
            val b = (argb and 0xFF) / 255f
            val maxC = max(r, max(g, b))
            val minC = min(r, min(g, b))
            val l = (maxC + minC) / 2f
            val d = maxC - minC
            if (d == 0f) return Hsl(0f, 0f, l)
            val s = d / (1f - abs(2f * l - 1f))
            val h = when (maxC) {
                r -> ((g - b) / d) % 6f
                g -> (b - r) / d + 2f
                else -> (r - g) / d + 4f
            } * 60f
            return Hsl((h + 360f) % 360f, s.coerceIn(0f, 1f), l)
        }

        fun toArgb(h: Float, s: Float, l: Float): Int {
            val c = (1f - abs(2f * l - 1f)) * s
            val hp = ((h % 360f) + 360f) % 360f / 60f
            val x = c * (1f - abs(hp % 2f - 1f))
            val (r1, g1, b1) = when (hp.toInt()) {
                0 -> Triple(c, x, 0f)
                1 -> Triple(x, c, 0f)
                2 -> Triple(0f, c, x)
                3 -> Triple(0f, x, c)
                4 -> Triple(x, 0f, c)
                else -> Triple(c, 0f, x)
            }
            val m = l - c / 2f
            fun ch(v: Float) = ((v + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
            return OPAQUE or (ch(r1) shl 16) or (ch(g1) shl 8) or ch(b1)
        }
    }
}

/**
 * Material-3-shaped colour roles generated from one seed colour. Tones are perceptual lightness (CIE L*),
 * so each foreground/background pair keeps its contrast whatever the seed's hue. This is a lightweight
 * stand-in for HCT: hue and saturation come from HSL, but lightness is solved against L*.
 */
data class ColorRoles(
    val primary: Int, val onPrimary: Int, val primaryContainer: Int, val onPrimaryContainer: Int,
    val secondary: Int, val onSecondary: Int, val secondaryContainer: Int, val onSecondaryContainer: Int,
    val tertiary: Int, val onTertiary: Int, val tertiaryContainer: Int, val onTertiaryContainer: Int,
    val background: Int, val onBackground: Int,
    val surface: Int, val onSurface: Int, val surfaceVariant: Int, val onSurfaceVariant: Int,
    val surfaceContainer: Int, val outline: Int,
) {
    companion object {
        private const val NEUTRAL_SATURATION = 0.08f
        private const val CHROMATIC_THRESHOLD = 0.08f

        fun fromSeed(seed: Int, dark: Boolean, amoled: Boolean = false): ColorRoles {
            val hsl = Hsl.of(seed)
            val chromatic = hsl.s >= CHROMATIC_THRESHOLD
            val hue = hsl.h
            val accent = if (chromatic) hsl.s.coerceIn(0.4f, 0.9f) else 0f
            val muted = accent * 0.4f
            val neutral = if (chromatic) NEUTRAL_SATURATION else 0f
            val tertiaryHue = hue + 60f

            fun p(tone: Int) = tone(hue, accent, tone)
            fun s(tone: Int) = tone(hue, muted, tone)
            fun t(tone: Int) = tone(tertiaryHue, accent * 0.6f, tone)
            fun n(tone: Int) = tone(hue, neutral, tone)
            fun nv(tone: Int) = tone(hue, neutral * 2f, tone)

            return if (!dark) {
                ColorRoles(
                    p(40), WHITE, p(90), p(10), s(40), WHITE, s(90), s(10), t(40), WHITE, t(90), t(10),
                    n(98), n(10), n(98), n(10), nv(90), nv(30), n(94), nv(50),
                )
            } else {
                val base = if (amoled) BLACK else n(6)
                ColorRoles(
                    p(80), p(20), p(30), p(90), s(80), s(20), s(30), s(90), t(80), t(20), t(30), t(90),
                    base, n(90), base, n(90), nv(30), nv(80), if (amoled) n(8) else n(12), nv(60),
                )
            }
        }

        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val BLACK = 0xFF000000.toInt()

        /** The colour of the given hue and saturation whose CIE L* equals [tone]. */
        internal fun tone(hue: Float, saturation: Float, tone: Int): Int {
            if (tone >= 100) return WHITE
            if (tone <= 0) return BLACK
            val target = luminanceOfTone(tone.toDouble())
            var lo = 0f
            var hi = 1f
            repeat(24) {
                val mid = (lo + hi) / 2f
                if (luminance(Hsl.toArgb(hue, saturation, mid)) < target) lo = mid else hi = mid
            }
            return Hsl.toArgb(hue, saturation, (lo + hi) / 2f)
        }

        private fun luminanceOfTone(l: Double): Double =
            if (l > 8.0) Math.pow((l + 16.0) / 116.0, 3.0) else l / 903.3

        private fun luminance(argb: Int): Double {
            fun channel(shift: Int): Double {
                val c = ((argb shr shift) and 0xFF) / 255.0
                return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
        }
    }
}
