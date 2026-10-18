// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.network

import io.mockk.every
import io.mockk.mockk
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HttpsTest {
    // --- isTransportAllowed --------------------------------------------------------------------

    @Test
    fun `https is always allowed`() {
        assertTrue(isTransportAllowed("https://feeds.example/a.xml", allowCleartext = false))
        assertTrue(isTransportAllowed("HTTPS://feeds.example/a.xml", allowCleartext = false))
        assertTrue(isTransportAllowed("https://feeds.example/a.xml", allowCleartext = true))
    }

    @Test
    fun `http needs the cleartext opt-in`() {
        assertFalse(isTransportAllowed("http://feeds.example/a.xml", allowCleartext = false))
        assertTrue(isTransportAllowed("http://feeds.example/a.xml", allowCleartext = true))
    }

    @Test
    fun `other schemes and unparseable input are refused even when cleartext is allowed`() {
        listOf("ftp://feeds.example/a.xml", "file:///sdcard/a.xml", "content://media/1", "feeds.example/a.xml", "", "not a url")
            .forEach { assertFalse(isTransportAllowed(it, allowCleartext = true), it) }
    }

    // --- upgradedToHttps -----------------------------------------------------------------------

    @Test
    fun `http becomes https`() {
        assertEquals("https://cdn.example.com/a.png?x=1", upgradedToHttps("http://cdn.example.com/a.png?x=1"))
    }

    @Test
    fun `the scheme is matched case-insensitively`() {
        assertEquals("https://cdn.example.com/a.png", upgradedToHttps("HTTP://cdn.example.com/a.png"))
    }

    @Test
    fun `https and other schemes need no upgrade`() {
        assertNull(upgradedToHttps("https://cdn.example.com/a.png"))
        assertNull(upgradedToHttps("file:///sdcard/a.png"))
        assertNull(upgradedToHttps("content://media/1"))
    }

    @Test
    fun `a host that merely starts with http is untouched`() {
        assertNull(upgradedToHttps("httpbin.org/image"))
    }

    // --- HttpsRedirectInterceptor --------------------------------------------------------------

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
        assertNull(respond(302, null).header("Location"))
        assertEquals(200, respond(200, null).code)
    }

    // --- withTransportPolicy -------------------------------------------------------------------

    @Test
    fun `the strict policy installs the upgrade and stops scheme-changing redirects`() {
        val client = OkHttpClient().withTransportPolicy(allowCleartext = false)

        assertFalse(client.followSslRedirects)
        assertTrue(client.networkInterceptors.any { it is HttpsRedirectInterceptor })
    }

    @Test
    fun `the cleartext opt-in leaves redirect behaviour alone`() {
        val client = OkHttpClient().withTransportPolicy(allowCleartext = true)

        assertTrue(client.followSslRedirects)
        assertTrue(client.networkInterceptors.none { it is HttpsRedirectInterceptor })
    }
}
