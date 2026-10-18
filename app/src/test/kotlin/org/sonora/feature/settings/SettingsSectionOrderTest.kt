// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SettingsSectionOrderTest {
    @Test
    fun `settings run from appearance to about`() {
        assertEquals(
            listOf(
                SettingsSection.Appearance, SettingsSection.Nextcloud, SettingsSection.BackgroundSync,
                SettingsSection.Downloads, SettingsSection.PodcastIndex, SettingsSection.Advanced, SettingsSection.About,
            ),
            SettingsSection.entries,
        )
    }
}
