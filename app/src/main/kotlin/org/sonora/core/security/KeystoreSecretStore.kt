// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.security.keystore.KeyPermanentlyInvalidatedException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.AEADBadTagException
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM with a non-exportable Android Keystore key; only ciphertext reaches SharedPreferences.
 * A stored value is discarded only when it can never be read again (tampered or malformed data, or a
 * key that was invalidated or wiped, e.g. by a lock-screen reset), so the user signs in again. Any other
 * Keystore fault may be transient: the value stays and [get] returns null for now.
 */
class KeystoreSecretStore(context: Context) : SecretStore {
    private val prefs = context.applicationContext.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    override fun get(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        val (iv, ciphertext) = decode(stored) ?: return discard(name)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: AEADBadTagException) { // wrong key (it was wiped and regenerated) or tampered data
            discard(name)
        } catch (_: KeyPermanentlyInvalidatedException) {
            discard(name)
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: RuntimeException) { // the Keystore can also fail with unchecked exceptions such as ProviderException
            null
        }
    }

    /** Returns false, leaving any earlier value in place, if the Keystore could not encrypt it. */
    override fun put(name: String, value: String): Boolean = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val encoded = listOf(cipher.iv, ciphertext).joinToString(SEPARATOR) { Base64.encodeToString(it, Base64.NO_WRAP) }
        prefs.edit().putString(name, encoded).apply()
        true
    } catch (_: GeneralSecurityException) {
        false
    } catch (_: RuntimeException) {
        false
    }

    private fun decode(stored: String): Pair<ByteArray, ByteArray>? {
        val parts = stored.split(SEPARATOR)
        if (parts.size != 2) return null
        return try {
            val (iv, ciphertext) = parts.map { Base64.decode(it, Base64.NO_WRAP) }
            if (iv.size == IV_BYTES) iv to ciphertext else null
        } catch (_: IllegalArgumentException) { // not valid Base64
            null
        }
    }

    private fun discard(name: String): String? {
        remove(name)
        return null
    }

    override fun remove(name: String) {
        prefs.edit().remove(name).apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "sonora_secret_store"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val IV_BYTES = 12
        const val SEPARATOR = ":"
    }
}
