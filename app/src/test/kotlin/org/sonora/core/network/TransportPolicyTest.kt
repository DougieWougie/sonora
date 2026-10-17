// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.network

import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TransportPolicyTest {
    @Test
    fun `strict policy blocks scheme-changing redirects`() {
        assertFalse(OkHttpClient().withTransportPolicy(allowCleartext = false).followSslRedirects)
    }

    @Test
    fun `cleartext opt-in leaves redirect behaviour alone`() {
        assertTrue(OkHttpClient().withTransportPolicy(allowCleartext = true).followSslRedirects)
    }
}
