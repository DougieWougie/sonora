// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.podcast

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.sonora.core.testing.TestTags

@RunWith(AndroidJUnit4::class)
class PodcastDescriptionTest {
    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun show(html: String) = composeTestRule.setContent { MaterialExpressiveTheme { DescriptionPreview(html) } }

    @Test
    fun previewShowsPlainTextInsteadOfTags() {
        show("<p>A show about <b>space</b> &amp; <a href=\"https://example.org\">cooking</a>.</p>")

        composeTestRule.onNodeWithTag(TestTags.PODCAST_DESCRIPTION).assertTextEquals("A show about space & cooking.")
    }

    @Test
    fun previewKeepsParagraphBreaks() {
        show("<p>First</p><p>Second</p>")

        composeTestRule.onNodeWithTag(TestTags.PODCAST_DESCRIPTION).assertTextEquals("First\n\nSecond")
    }
}
