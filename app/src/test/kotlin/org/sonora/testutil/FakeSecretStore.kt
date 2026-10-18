// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.testutil

import org.sonora.core.security.SecretStore

class FakeSecretStore : SecretStore {
    val values = mutableMapOf<String, String>()
    override fun get(name: String) = values[name]
    override fun put(name: String, value: String) {
        values[name] = value
    }
    override fun remove(name: String) {
        values.remove(name)
    }
}
