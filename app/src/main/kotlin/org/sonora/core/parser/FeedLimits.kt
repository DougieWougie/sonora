// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.parser

import java.io.FilterInputStream
import java.io.InputStream

/** Caps on untrusted feed and OPML input. Generous: no legitimate feed comes near them. */
object FeedLimits {
    /** Decompressed bytes read from one document (OkHttp decompresses gzip transparently). */
    const val MAX_BYTES: Long = 50L * 1024 * 1024

    /** Characters in any single text field; the UI truncates show notes at 100,000. */
    const val MAX_FIELD_CHARS: Int = 200_000

    /** Items parsed from one feed. */
    const val MAX_ITEMS: Int = 20_000
}

/**
 * A feed or OPML document exceeded a [FeedLimits] cap. Deliberately not an `IOException`: retrying would
 * hit the same limit, so the sync pipeline treats it as a permanent failure.
 */
class FeedLimitExceededException(message: String) : Exception(message)

/** Streams [input] through, throwing once more than [maxBytes] have been read. Nothing is buffered. */
internal class LimitedInputStream(input: InputStream, private val maxBytes: Long) : FilterInputStream(input) {
    private var count = 0L

    override fun read(): Int = super.read().also { if (it != -1) add(1) }

    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) add(it.toLong()) }

    override fun skip(n: Long): Long = super.skip(n).also { if (it > 0) add(it) }

    private fun add(n: Long) {
        count += n
        if (count > maxBytes) throw FeedLimitExceededException("Document is larger than $maxBytes bytes")
    }
}
