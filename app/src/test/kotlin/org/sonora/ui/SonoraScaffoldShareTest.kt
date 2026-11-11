// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.ui

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.sonora.feature.player.PlayerEpisode
import org.sonora.feature.player.PlayerEvent
import org.sonora.feature.player.PlayerUiState

/**
 * JVM-only: reads the started activity through a Robolectric shadow, which a device cannot do, so this
 * lives outside `sharedTest` (that source set is also compiled into `androidTest`).
 */
@RunWith(AndroidJUnit4::class)
class SonoraScaffoldShareTest {
    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val events = Channel<PlayerEvent>(Channel.BUFFERED)

    @Test
    fun `a share event opens a chooser`() {
        val player = PlayerUiState(episode = PlayerEpisode(id = 1, title = "The Episode", podcastTitle = "The Podcast", artworkUrl = null))
        composeTestRule.setContent {
            MaterialExpressiveTheme {
                SonoraScaffold(player, events.receiveAsFlow(), {}, NavigationLayout.BottomBar, NavBadges()) { navController, _, modifier ->
                    NavHost(navController, startDestination = SubscriptionsRoute, modifier = modifier) {
                        composable<SubscriptionsRoute> { Text("Subscriptions screen") }
                    }
                }
            }
        }

        events.trySend(PlayerEvent.Share("The Episode", "The Episode — The Podcast\nhttps://example.org/ep"))
        composeTestRule.waitForIdle()

        val started = shadowOf(composeTestRule.activity).nextStartedActivity
        assertNotNull(started)
        assertEquals(Intent.ACTION_CHOOSER, started.action)
    }
}
