// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.security

/** Storage for credentials such as the Nextcloud app password and PodcastIndex API secret. */
interface SecretStore {
    fun get(name: String): String?
    fun put(name: String, value: String)
    fun remove(name: String)
}
