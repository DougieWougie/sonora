// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora

import android.Manifest
import android.content.ComponentName
import org.sonora.core.media.PodcastMediaSessionService
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.sonora.testutil.FakeAndroidKeyStore

@UnstableApi
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class)
class MainActivityTest {
    @get:Rule val hilt = HiltAndroidRule(this)

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            ApplicationProvider.getApplicationContext(),
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        FakeAndroidKeyStore.install()
        // Robolectric binds with a null component name, which Media3 cannot take; the service itself
        // is covered by PodcastMediaSessionServiceTest.
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .declareComponentUnbindable(ComponentName(ApplicationProvider.getApplicationContext<android.app.Application>(), PodcastMediaSessionService::class.java))
    }

    @After
    fun tearDown() = FakeAndroidKeyStore.uninstall()

    @Test
    fun `the activity starts, connects a media controller and stops it again`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()

        assertNotNull(controller.get())

        controller.pause().stop().destroy()
    }

    @Test
    fun `it asks for the notification permission when it is missing`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()

        // Not granted by default, so the permission request was launched.
        assertNotNull(shadowOf(controller.get()).lastRequestedPermission)
        controller.pause().stop().destroy()
    }

    @Test
    fun `it does not ask again when the permission is already granted`() {
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()

        org.junit.Assert.assertNull(shadowOf(controller.get()).lastRequestedPermission)
        controller.pause().stop().destroy()
    }
}
