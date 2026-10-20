// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.sonora.testutil.FakeAndroidKeyStore

@RunWith(AndroidJUnit4::class)
class KeystoreSecretStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)
    private val store get() = KeystoreSecretStore(context)

    @Before
    fun clear() {
        FakeAndroidKeyStore.install()
        prefs.edit().clear().commit()
    }

    @After
    fun tearDown() = FakeAndroidKeyStore.uninstall()

    @Test
    fun `a stored secret is read back`() {
        store.put("token", "s3cret")

        assertEquals("s3cret", store.get("token"))
    }

    @Test
    fun `a secret survives a new store instance`() {
        store.put("token", "s3cret")

        assertEquals("s3cret", KeystoreSecretStore(context).get("token"))
    }

    @Test
    fun `only ciphertext reaches the preferences`() {
        store.put("token", "s3cret")

        val raw = prefs.getString("token", null)!!
        assertFalse(raw.contains("s3cret"))
    }

    @Test
    fun `the same value encrypts differently each time`() {
        store.put("a", "s3cret")
        store.put("b", "s3cret")

        assertNotEquals(prefs.getString("a", null), prefs.getString("b", null))
    }

    @Test
    fun `an unknown name has no secret`() {
        assertNull(store.get("missing"))
    }

    @Test
    fun `a put replaces the previous secret`() {
        store.put("token", "old")
        store.put("token", "new")

        assertEquals("new", store.get("token"))
    }

    @Test
    fun `a removed secret is gone`() {
        store.put("token", "s3cret")

        store.remove("token")

        assertNull(store.get("token"))
        assertFalse(prefs.contains("token"))
    }

    @Test
    fun `a value that cannot be decrypted is discarded`() {
        store.put("token", "s3cret")
        val (iv, ciphertext) = prefs.getString("token", null)!!.split(":")
        val flipped = (if (ciphertext.first() == 'A') 'B' else 'A') + ciphertext.drop(1)
        prefs.edit().putString("token", "$iv:$flipped").commit()

        assertNull(store.get("token"))
        assertFalse(prefs.contains("token"))
    }

    @Test
    fun `a malformed value is discarded`() {
        prefs.edit().putString("token", "no-separator").commit()

        assertNull(store.get("token"))
        assertFalse(prefs.contains("token"))
    }

    @Test
    fun `a secret whose key was wiped is discarded`() {
        store.put("token", "s3cret")

        FakeAndroidKeyStore.wipe()

        assertNull(store.get("token"))
        assertFalse(prefs.contains("token"))
    }
}
