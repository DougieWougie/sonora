// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.testutil

import mockwebserver3.MockWebServer
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * Starts a fresh [server] before every test and closes it after:
 * `@JvmField @RegisterExtension val web = MockWebServerExtension()`.
 */
class MockWebServerExtension : BeforeEachCallback, AfterEachCallback {
    val server = MockWebServer()

    override fun beforeEach(context: ExtensionContext) = server.start()
    override fun afterEach(context: ExtensionContext) = server.close()
}
