// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.ui.theme

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.request.SuccessResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArtworkColorExtractorTest {
    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun solid(color: Int): Bitmap =
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @After
    fun tearDown() = SingletonImageLoader.reset()

    // Palette quantises colours, so the seed is the image colour give or take a few levels.
    private fun assertCloseTo(expected: Int, actual: Int) {
        for (channel in listOf(Color::red, Color::green, Color::blue)) {
            assertEquals(channel(expected).toDouble(), channel(actual).toDouble(), 8.0)
        }
    }

    // --- seedFrom ------------------------------------------------------------------------------

    @Test
    fun `a vivid image gives a seed close to its colour`() = runBlocking {
        val seed = ArtworkColorExtractor.seedFrom(solid(Color.rgb(200, 30, 40)))

        assertNotNull(seed)
        assertCloseTo(Color.rgb(200, 30, 40), seed!!)
    }

    @Test
    fun `an image with no usable colour gives no seed`() = runBlocking {
        assertNull(ArtworkColorExtractor.seedFrom(solid(Color.TRANSPARENT)))
    }

    // --- rememberSeed --------------------------------------------------------------------------

    private fun loaderReturning(block: (coil3.request.ImageRequest) -> ImageResult) {
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(composeTestRule.activity)
                .components {
                    add(Interceptor { chain -> block(chain.request) })
                }
                .build(),
        )
    }

    private var seed: Int? = null
    private var url by mutableStateOf<String?>(null)

    private fun setContent() = composeTestRule.setContent { seed = ArtworkColorExtractor.rememberSeed(url) }

    @Test
    fun `no artwork means no seed`() {
        loaderReturning { error("should not load") }

        setContent()
        composeTestRule.waitForIdle()

        assertNull(seed)
    }

    @Test
    fun `loaded artwork becomes the seed`() {
        loaderReturning { SuccessResult(solid(Color.rgb(200, 30, 40)).asImage(), it, DataSource.MEMORY) }
        url = "https://art/cover.jpg"

        setContent()
        composeTestRule.waitUntil(5_000) { seed != null }

        assertCloseTo(Color.rgb(200, 30, 40), seed!!)
    }

    @Test
    fun `artwork that fails to load leaves no seed`() {
        var requested = false
        loaderReturning { requested = true; ErrorResult(null, it, RuntimeException("404")) }
        url = "https://art/missing.jpg"

        setContent()
        composeTestRule.waitUntil(5_000) { requested }
        composeTestRule.waitForIdle()

        assertNull(seed)
    }

    @Test
    fun `dropping the artwork clears the seed`() {
        loaderReturning { SuccessResult(solid(Color.rgb(200, 30, 40)).asImage(), it, DataSource.MEMORY) }
        url = "https://art/cover.jpg"
        setContent()
        composeTestRule.waitUntil(5_000) { seed != null }

        composeTestRule.runOnIdle { url = null }
        composeTestRule.waitForIdle()

        assertNull(seed)
    }
}
