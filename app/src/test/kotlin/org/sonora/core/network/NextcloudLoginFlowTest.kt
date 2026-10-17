// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.network

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class NextcloudLoginFlowTest {
    private val server = MockWebServer()
    private lateinit var flow: NextcloudLoginFlow
    private lateinit var base: String

    @BeforeEach
    fun setUp() {
        server.start()
        base = server.url("/").toString().trimEnd('/')
        flow = NextcloudLoginFlow(OkHttpClient(), userAgent = "Sonora-Test", allowCleartext = true)
    }

    @AfterEach
    fun tearDown() = server.close()

    private fun startResponse() = MockResponse.Builder().body(
        """{"poll":{"token":"tok123","endpoint":"$base/index.php/login/v2/poll"},
            "login":"$base/index.php/login/v2/flow/abc"}""",
    ).build()

    private fun credentialsResponse() = MockResponse.Builder().body(
        """{"server":"$base","loginName":"alice","appPassword":"s3cret-app-pass"}""",
    ).build()

    private fun pending() = MockResponse.Builder().code(404).build()

    @Test
    fun `start posts to login v2 and returns the login url and poll details`() = runTest {
        server.enqueue(startResponse())

        val session = flow.start(base)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/index.php/login/v2", request.target)
        assertEquals("Sonora-Test", request.headers["User-Agent"])
        assertEquals(LoginFlowSession("$base/index.php/login/v2/flow/abc", "$base/index.php/login/v2/poll", "tok123"), session)
    }

    @Test
    fun `start tolerates a trailing slash on the server url`() = runTest {
        server.enqueue(startResponse())
        flow.start("$base/")
        assertEquals("/index.php/login/v2", server.takeRequest().target)
    }

    @Test
    fun `poll returns null while the user has not logged in`() = runTest {
        server.enqueue(pending())
        val session = LoginFlowSession("l", "$base/index.php/login/v2/poll", "tok123")

        assertNull(flow.poll(session))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("token=tok123", request.body!!.utf8())
    }

    @Test
    fun `poll returns server loginName and appPassword once approved`() = runTest {
        server.enqueue(credentialsResponse())
        val session = LoginFlowSession("l", "$base/index.php/login/v2/poll", "tok123")

        assertEquals(NextcloudCredentials(base, "alice", "s3cret-app-pass"), flow.poll(session))
    }

    @Test
    fun `awaitCredentials keeps polling until approved`() = runTest {
        server.enqueue(pending())
        server.enqueue(pending())
        server.enqueue(credentialsResponse())
        val session = LoginFlowSession("l", "$base/index.php/login/v2/poll", "tok123")

        val creds = flow.awaitCredentials(session, pollIntervalMillis = 1_000, timeoutMillis = 60_000)

        assertEquals("alice", creds.loginName)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `awaitCredentials gives up after the timeout`() = runTest {
        repeat(20) { server.enqueue(pending()) }
        val session = LoginFlowSession("l", "$base/index.php/login/v2/poll", "tok123")

        assertThrows<LoginFlowException> {
            flow.awaitCredentials(session, pollIntervalMillis = 1_000, timeoutMillis = 3_000)
        }
    }

    @Test
    fun `start fails clearly when the server is not Nextcloud`() = runTest {
        server.enqueue(MockResponse.Builder().code(404).build())
        assertThrows<LoginFlowException> { flow.start(base) }
    }


    @Test
    fun `cleartext servers are refused unless explicitly allowed`() = runTest {
        val strict = NextcloudLoginFlow(OkHttpClient())
        assertThrows<LoginFlowException> { strict.start(base) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a poll endpoint downgraded to cleartext is refused`() = runTest {
        val strict = NextcloudLoginFlow(OkHttpClient())
        val session = LoginFlowSession("l", "$base/index.php/login/v2/poll", "tok")
        assertThrows<LoginFlowException> { strict.poll(session) }
        assertEquals(0, server.requestCount)
    }
}
