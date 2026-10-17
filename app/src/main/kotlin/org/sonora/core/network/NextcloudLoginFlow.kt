// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.network

import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/** The URL the user must open in a browser, plus what is needed to poll for the result. */
data class LoginFlowSession(val loginUrl: String, val pollEndpoint: String, val pollToken: String)

data class NextcloudCredentials(val server: String, val loginName: String, val appPassword: String)

class LoginFlowException(message: String, cause: Throwable? = null) : Exception(message, cause)

@Serializable
private data class StartDto(val poll: PollDto, val login: String)

@Serializable
private data class PollDto(val token: String, val endpoint: String)

@Serializable
private data class CredentialsDto(val server: String, val loginName: String, val appPassword: String)

/** Nextcloud Login Flow v2: obtains an app password without the app ever seeing the user's real password. */
class NextcloudLoginFlow(
    private val client: OkHttpClient,
    private val userAgent: String = "Sonora",
    private val allowCleartext: Boolean = false,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun start(serverUrl: String): LoginFlowSession {
        val url = serverUrl.trimEnd('/') + "/index.php/login/v2"
        val body = post(url, FormBody.Builder().build()) ?: throw LoginFlowException("Server does not support Login Flow v2")
        val dto = decode<StartDto>(body)
        return LoginFlowSession(dto.login, dto.poll.endpoint, dto.poll.token)
    }

    /** One poll attempt: null while the user has not finished logging in yet. */
    suspend fun poll(session: LoginFlowSession): NextcloudCredentials? {
        val form = FormBody.Builder().add("token", session.pollToken).build()
        val body = post(session.pollEndpoint, form) ?: return null
        val dto = decode<CredentialsDto>(body)
        return NextcloudCredentials(dto.server, dto.loginName, dto.appPassword)
    }

    suspend fun awaitCredentials(
        session: LoginFlowSession,
        pollIntervalMillis: Long = 2_000,
        timeoutMillis: Long = 20 * 60_000,
    ): NextcloudCredentials {
        var waited = 0L
        while (true) {
            poll(session)?.let { return it }
            if (waited >= timeoutMillis) throw LoginFlowException("Timed out waiting for login approval")
            delay(pollIntervalMillis)
            waited += pollIntervalMillis
        }
    }

    /** Returns the response body on 200, null on 404 (not ready / not supported), and throws otherwise. */
    private suspend fun post(url: String, body: okhttp3.RequestBody): String? {
        if (!isTransportAllowed(url, allowCleartext)) throw LoginFlowException("Refusing non-HTTPS URL: $url")
        val request = Request.Builder().url(url).header("User-Agent", userAgent).post(body).build()
        return withContext(ioDispatcher) {
            try {
                client.newCall(request).execute().use { response ->
                    when (response.code) {
                        200 -> response.body.string()
                        404 -> null
                        else -> throw LoginFlowException("Unexpected HTTP ${response.code} from $url")
                    }
                }
            } catch (e: IOException) {
                throw LoginFlowException("Network error contacting $url", e)
            }
        }
    }

    private inline fun <reified T> decode(text: String): T = try {
        syncJson.decodeFromString<T>(text)
    } catch (e: SerializationException) {
        throw LoginFlowException("Malformed Login Flow response", e)
    }
}
