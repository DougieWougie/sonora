// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.network

import io.mockk.every
import io.mockk.mockk
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HttpsRedirectInterceptorTest {
    private val request = Request.Builder().url("https://feeds.example/a.mp3").build()

    private fun respond(code: Int, location: String?): Response {
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request
        every { chain.proceed(request) } returns Response.Builder()
            .request(request).protocol(Protocol.HTTP_1_1).code(code).message("x")
            .apply { if (location != null) header("Location", location) }
            .build()
        return HttpsRedirectInterceptor().intercept(chain)
    }

    @Test
    fun `an http redirect target is followed over https instead`() {
        assertEquals("https://cdn.example/a.mp3?t=1", respond(302, "http://cdn.example/a.mp3?t=1").header("Location"))
    }

    @Test
    fun `https and relative redirect targets are left alone`() {
        assertEquals("https://cdn.example/a.mp3", respond(302, "https://cdn.example/a.mp3").header("Location"))
        assertEquals("/b.mp3", respond(301, "/b.mp3").header("Location"))
    }

    @Test
    fun `responses without a redirect are untouched`() {
        assertEquals(null, respond(302, null).header("Location"))
        assertEquals(200, respond(200, null).code)
    }

    @Test
    fun `the strict policy installs the upgrade, the cleartext opt-in does not`() {
        assertTrue(OkHttpClient().withTransportPolicy(false).networkInterceptors.any { it is HttpsRedirectInterceptor })
        assertTrue(OkHttpClient().withTransportPolicy(true).networkInterceptors.none { it is HttpsRedirectInterceptor })
    }
}
