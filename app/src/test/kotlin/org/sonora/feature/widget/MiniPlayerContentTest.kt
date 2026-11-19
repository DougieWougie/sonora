// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.widget

import android.content.Context
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasContentDescription
import androidx.glance.testing.unit.hasTestTag
import androidx.glance.testing.unit.hasText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MiniPlayerContentTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val playing = MiniPlayerWidgetState.Loaded(
        episodeId = 1,
        episodeTitle = "Ep one",
        podcastTitle = "Show",
        artworkUrl = null,
        isPlaying = true,
    )

    @Test
    fun `idle widget says nothing is playing and offers no controls`() = runTest {
        runGlanceAppWidgetUnitTest {
            setContext(context)
            provideComposable { GlanceTheme { MiniPlayerContent(MiniPlayerWidgetState.Idle, artwork = null) } }

            onNode(hasText("Nothing playing")).assertExists()
            onNode(hasContentDescription("Pause")).assertDoesNotExist()
            onNode(hasContentDescription("Next")).assertDoesNotExist()
            onNode(hasContentDescription("Back 10 seconds")).assertDoesNotExist()
            onNode(hasContentDescription("Forward 30 seconds")).assertDoesNotExist()
        }
    }

    @Test
    fun `loaded widget shows the episode, the show and a pause control while playing`() = runTest {
        runGlanceAppWidgetUnitTest {
            setContext(context)
            provideComposable { GlanceTheme { MiniPlayerContent(playing, artwork = null) } }

            onNode(hasText("Ep one")).assertExists()
            onNode(hasText("Show")).assertExists()
            onNode(hasContentDescription("Pause")).assertExists()
            onNode(hasContentDescription("Previous")).assertExists()
            onNode(hasContentDescription("Next")).assertExists()
        }
    }

    @Test
    fun `loaded widget offers rewind and forward`() = runTest {
        runGlanceAppWidgetUnitTest {
            setContext(context)
            provideComposable { GlanceTheme { MiniPlayerContent(playing, artwork = null) } }

            onNode(hasContentDescription("Back 10 seconds")).assertExists()
            onNode(hasContentDescription("Forward 30 seconds")).assertExists()
        }
    }

    @Test
    fun `loaded widget offers play while paused`() = runTest {
        runGlanceAppWidgetUnitTest {
            setContext(context)
            provideComposable { GlanceTheme { MiniPlayerContent(playing.copy(isPlaying = false), artwork = null) } }

            onNode(hasContentDescription("Play")).assertExists()
            onNode(hasContentDescription("Pause")).assertDoesNotExist()
        }
    }

    @Test
    fun `loaded widget shows artwork when it has some`() = runTest {
        val bitmap = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
        runGlanceAppWidgetUnitTest {
            setContext(context)
            provideComposable { GlanceTheme { MiniPlayerContent(playing, artwork = bitmap) } }

            onNode(hasTestTag(MINI_PLAYER_ARTWORK_TAG)).assertExists()
        }
    }

    @Test
    fun `loaded widget leaves out the artwork slot when there is none`() = runTest {
        runGlanceAppWidgetUnitTest {
            setContext(context)
            provideComposable { GlanceTheme { MiniPlayerContent(playing, artwork = null) } }

            onNode(hasTestTag(MINI_PLAYER_ARTWORK_TAG)).assertDoesNotExist()
        }
    }
}
