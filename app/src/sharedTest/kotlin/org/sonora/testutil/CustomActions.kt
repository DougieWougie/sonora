// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.testutil

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeContentTestRule

/** Runs the custom accessibility action with this [label], as a screen reader would. */
fun SemanticsNodeInteraction.performCustomAction(rule: ComposeContentTestRule, label: String) {
    val action = fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == label }.action
    rule.runOnUiThread { action() }
    rule.waitForIdle()
}
