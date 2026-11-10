// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** docs/ARCHITECTURE.md: `core` does not depend on `ui` or `feature`. Checked by reading the sources. */
class LayeringTest {
    private val core = File("src/main/kotlin/org/sonora/core")

    @Test
    fun `core does not import ui or feature`() {
        assertTrue(core.isDirectory, "expected ${core.absolutePath} (tests run from the app module)")
        val forbidden = Regex("""^\s*import\s+org\.sonora\.(ui|feature)\b""")

        val offenders = core.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { i, line ->
                    if (forbidden.containsMatchIn(line)) "${file.relativeTo(core)}:${i + 1}: ${line.trim()}" else null
                }
            }
            .toList()

        assertEquals(emptyList<String>(), offenders)
    }
}
