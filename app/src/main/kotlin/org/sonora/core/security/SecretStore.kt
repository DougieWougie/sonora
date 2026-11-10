// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.security

/** Storage for credentials such as the Nextcloud app password and PodcastIndex API secret. */
interface SecretStore {
    fun get(name: String): String?
    /** Returns false if the secret could not be stored (e.g. the Keystore failed); nothing is stored then. */
    fun put(name: String, value: String): Boolean
    fun remove(name: String)
}
