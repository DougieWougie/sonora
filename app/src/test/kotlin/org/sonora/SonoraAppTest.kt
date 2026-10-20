// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora

import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.mockk
import okhttp3.OkHttpClient
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.sonora.feature.settings.AppSettings
import org.sonora.testutil.FakeSecretStore
import org.sonora.ui.HttpsUpgradeInterceptor

@RunWith(AndroidJUnit4::class)
class SonoraAppTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val workerFactory = mockk<HiltWorkerFactory>()
    private val settings = AppSettings(context, FakeSecretStore())
    private val app = SonoraApp()

    @Before
    fun inject() {
        app.workerFactory = workerFactory
        app.settings = settings
        app.okHttpClient = OkHttpClient()
    }

    @Test
    fun `workers are built by the Hilt worker factory`() {
        assertSame(workerFactory, app.workManagerConfiguration.workerFactory)
    }

    @Test
    fun `artwork is upgraded to https unless cleartext is allowed`() {
        settings.allowCleartext = false

        val interceptors = app.newImageLoader(context).components.interceptors

        assertTrue(interceptors.any { it is HttpsUpgradeInterceptor })
    }

    @Test
    fun `artwork keeps cleartext urls when the user allows cleartext`() {
        settings.allowCleartext = true

        val interceptors = app.newImageLoader(context).components.interceptors

        assertTrue(interceptors.none { it is HttpsUpgradeInterceptor })
    }
}
