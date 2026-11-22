// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.ui.theme

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.SuccessResult
import coil3.size.Size
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Samples a bitmap with Palette and hands the colours to the (unit-tested) [SeedColorSelector]. */
object ArtworkColorExtractor {
    suspend fun seedFrom(bitmap: Bitmap): Int? = withContext(Dispatchers.Default) {
        val palette = Palette.from(bitmap).maximumColorCount(PALETTE_COLORS).generate()
        SeedColorSelector.pick(palette.swatches.map { ColorSample(it.rgb, it.population) })?.argb
    }

    private const val PALETTE_COLORS = 16
    const val SAMPLE_SIZE_PX = 128
    private val seedCache = android.util.LruCache<String, Int>(32)

    /** Loads [url] small and returns its seed colour, or null while loading / on failure. */
    @Composable
    fun rememberSeed(url: String?): Int? {
        val context = LocalContext.current
        var seed by remember(url) { mutableStateOf(url?.let { seedCache.get(it) }) }
        LaunchedEffect(url) {
            if (url == null) {
                seed = null
                return@LaunchedEffect
            }
            seedCache.get(url)?.let {
                seed = it
                return@LaunchedEffect
            }
            seed = null
            val request = ImageRequest.Builder(context)
                .data(url)
                .size(Size(SAMPLE_SIZE_PX, SAMPLE_SIZE_PX))
                .allowHardware(false) // Palette reads pixels
                .build()
            val result = SingletonImageLoader.get(context).execute(request)
            if (result is SuccessResult) {
                seedFrom(result.image.toBitmap())?.let { extracted ->
                    seedCache.put(url, extracted)
                    seed = extracted
                }
            }
        }
        return seed
    }
}
