// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.ImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.size.Size
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HttpsUpgradeInterceptorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val loader = ImageLoader.Builder(context).build()

    private fun runInterceptor(data: Any, allowCleartext: Boolean = false): Pair<Any?, ImageResult> {
        var seen: Any? = null
        val interceptor = HttpsUpgradeInterceptor(allowCleartext = allowCleartext)
        val request = ImageRequest.Builder(context).data(data).build()
        val chain = object : Interceptor.Chain {
            override val request: ImageRequest = request
            override val size: Size = Size.ORIGINAL
            override fun withSize(size: Size): Interceptor.Chain = this
            override fun withRequest(request: ImageRequest): Interceptor.Chain {
                val updated = request
                return object : Interceptor.Chain by this {
                    override val request: ImageRequest = updated
                    override suspend fun proceed(): ImageResult {
                        seen = updated.data
                        return ErrorResult(null, updated, IllegalStateException("stub"))
                    }
                }
            }

            override suspend fun proceed(): ImageResult {
                seen = request.data
                return ErrorResult(null, request, IllegalStateException("stub"))
            }
        }
        var result: ImageResult? = null
        runTest { result = interceptor.intercept(chain) }
        return seen to result!!
    }

    @Test
    fun `upgrades http urls to https when cleartext is disallowed`() {
        val (seen, _) = runInterceptor("http://cdn.example/art.jpg", allowCleartext = false)
        assertEquals("https://cdn.example/art.jpg", seen)
    }

    @Test
    fun `rejects non-http artwork schemes without proceeding`() {
        for (bad in listOf("file:///data/data/org.sonora/shared_prefs/secrets.xml", "content://com.example/secret", "data:image/png;base64,AA==")) {
            val (seen, result) = runInterceptor(bad)
            assertEquals("should not proceed for $bad", null, seen)
            assertTrue("expected ErrorResult for $bad", result is ErrorResult)
        }
    }
}
