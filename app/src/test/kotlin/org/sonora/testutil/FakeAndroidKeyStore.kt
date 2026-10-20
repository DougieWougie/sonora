// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.testutil

import android.security.keystore.KeyGenParameterSpec
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStore.LoadStoreParameter
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** An in-memory stand-in for the `AndroidKeyStore` JCA provider, which Robolectric does not supply. */
object FakeAndroidKeyStore {
    private val keys = mutableMapOf<String, SecretKey>()
    private val provider = object : Provider("AndroidKeyStore", 1.0, "In-memory Android Keystore for tests") {
        init {
            put("KeyStore.AndroidKeyStore", FakeKeyStoreSpi::class.java.name)
            put("KeyGenerator.AES", FakeKeyGeneratorSpi::class.java.name)
        }
    }

    fun install() {
        keys.clear()
        Security.addProvider(provider)
    }

    fun uninstall() {
        Security.removeProvider(provider.name)
        keys.clear()
    }

    /** Forgets every key, as when a lock-screen reset wipes the real keystore. */
    fun wipe() = keys.clear()

    class FakeKeyStoreSpi : KeyStoreSpi() {
        override fun engineGetKey(alias: String, password: CharArray?): Key? = keys[alias]
        override fun engineContainsAlias(alias: String) = alias in keys
        override fun engineIsKeyEntry(alias: String) = alias in keys
        override fun engineAliases(): Enumeration<String> = Collections.enumeration(keys.keys.toList())
        override fun engineSize() = keys.size
        override fun engineDeleteEntry(alias: String) { keys.remove(alias) }
        override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
        override fun engineLoad(param: LoadStoreParameter?) = Unit
        override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
        override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null
        override fun engineGetCertificate(alias: String): Certificate? = null
        override fun engineGetCreationDate(alias: String): Date? = null
        override fun engineIsCertificateEntry(alias: String) = false
        override fun engineGetCertificateAlias(cert: Certificate?): String? = null
        override fun engineSetCertificateEntry(alias: String?, cert: Certificate?) = unsupported()
        override fun engineSetKeyEntry(alias: String?, key: Key?, password: CharArray?, chain: Array<out Certificate>?) =
            unsupported()
        override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<out Certificate>?) = unsupported()
        private fun unsupported(): Nothing = throw UnsupportedOperationException()
    }

    class FakeKeyGeneratorSpi : KeyGeneratorSpi() {
        private var spec: KeyGenParameterSpec? = null

        override fun engineInit(random: SecureRandom?) = throw UnsupportedOperationException()
        override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom?) {
            spec = params as KeyGenParameterSpec
        }
        override fun engineInit(keysize: Int, random: SecureRandom?) = throw UnsupportedOperationException()
        override fun engineGenerateKey(): SecretKey {
            val spec = checkNotNull(spec)
            val bytes = ByteArray(spec.keySize / Byte.SIZE_BITS).also { SecureRandom().nextBytes(it) }
            return SecretKeySpec(bytes, "AES").also { keys[spec.keystoreAlias] = it }
        }
    }
}
