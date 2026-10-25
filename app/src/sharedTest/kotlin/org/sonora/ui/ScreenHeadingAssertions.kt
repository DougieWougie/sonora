// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.ui

import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading

/** A screen's title is announced to screen readers as a heading but takes no visible space. */
fun SemanticsNodeInteractionsProvider.assertScreenHeading(title: String) {
    onAllNodes(isHeading() and hasContentDescription(title)).assertCountEquals(1)
    // No visible title text; nav bar labels aren't headings, so they don't match.
    onAllNodes(hasText(title) and isHeading()).assertCountEquals(0)
}
