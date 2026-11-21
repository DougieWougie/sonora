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
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
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

    private val withQueue = playing.copy(
        queue = listOf(QueuedEpisode(2, "Queued one"), QueuedEpisode(3, "Queued two")),
        queueSize = 5,
    )

    @Test
    fun `large widget lists the queue and how many more there are`() = runTest {
        runGlanceAppWidgetUnitTest {
            setContext(context)
            provideComposable { GlanceTheme { MiniPlayerContent(withQueue, artwork = null, layout = WidgetLayout.Large) } }

            onNode(hasText("Up next")).assertExists()
            onNode(hasText("Queued one")).assertExists()
            onNode(hasText("Queued two")).assertExists()
            onNode(hasText("+3 more")).assertExists()
        }
    }

    @Test
    fun `large widget has no queue section when the queue is empty`() = runTest {
        runGlanceAppWidgetUnitTest {
            setContext(context)
            provideComposable { GlanceTheme { MiniPlayerContent(playing, artwork = null, layout = WidgetLayout.Large) } }

            onNode(hasText("Up next")).assertDoesNotExist()
        }
    }

    @Test
    fun `compact widget never shows the queue`() = runTest {
        runGlanceAppWidgetUnitTest {
            setContext(context)
            provideComposable { GlanceTheme { MiniPlayerContent(withQueue, artwork = null, layout = WidgetLayout.Compact) } }

            onNode(hasText("Ep one")).assertExists()
            onNode(hasText("Up next")).assertDoesNotExist()
            onNode(hasText("Queued one")).assertDoesNotExist()
        }
    }

    @Test
    fun `compact widget keeps play, rewind and forward`() = runTest {
        runGlanceAppWidgetUnitTest {
            setContext(context)
            provideComposable { GlanceTheme { MiniPlayerContent(playing, artwork = null, layout = WidgetLayout.Compact) } }

            onNode(hasContentDescription("Pause")).assertExists()
            onNode(hasContentDescription("Back 10 seconds")).assertExists()
            onNode(hasContentDescription("Forward 30 seconds")).assertExists()
            onNode(hasContentDescription("Previous")).assertDoesNotExist()
            onNode(hasContentDescription("Next")).assertDoesNotExist()
        }
    }

    @Test
    fun `a recalled episode offers only play, in either layout`() = runTest {
        val recalled = playing.copy(isPlaying = false, active = false)
        for (layout in WidgetLayout.entries) {
            runGlanceAppWidgetUnitTest {
                setContext(context)
                provideComposable { GlanceTheme { MiniPlayerContent(recalled, artwork = null, layout = layout) } }

                onNode(hasText("Ep one")).assertExists()
                onNode(hasContentDescription("Play")).assertExists()
                onNode(hasContentDescription("Next")).assertDoesNotExist()
                onNode(hasContentDescription("Previous")).assertDoesNotExist()
                onNode(hasContentDescription("Back 10 seconds")).assertDoesNotExist()
                onNode(hasContentDescription("Forward 30 seconds")).assertDoesNotExist()
            }
        }
    }

    @Test
    fun `layout follows the widget's size`() {
        assertEquals(WidgetLayout.Compact, layoutFor(androidx.compose.ui.unit.DpSize(110.dp, 110.dp)))
        assertEquals(WidgetLayout.Compact, layoutFor(androidx.compose.ui.unit.DpSize(250.dp, 110.dp)))
        assertEquals(WidgetLayout.Large, layoutFor(androidx.compose.ui.unit.DpSize(250.dp, 250.dp)))
    }
}
