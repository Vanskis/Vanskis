package com.vanskis.liquidpocket.engine

/**
 * Turns a stream of token byte pieces into text without breaking multibyte characters that a
 * token boundary splits: an incomplete trailing UTF-8 sequence is held back until the next piece.
 * (Plays the role of the valid-UTF-8 buffering in upstream llama.android's ai_chat.cpp.)
 */
internal class Utf8Accumulator {
    private var pending = ByteArray(0)

    /** Appends [bytes] and returns the text that is now complete (possibly empty). */
    fun append(bytes: ByteArray): String {
        val buf = if (pending.isEmpty()) bytes else pending + bytes
        val cut = completePrefixLength(buf)
        pending = buf.copyOfRange(cut, buf.size)
        return if (cut == 0) "" else String(buf, 0, cut, Charsets.UTF_8)
    }

    internal companion object {
        /** Length of the longest prefix of [buf] that does not end inside a multibyte sequence. */
        fun completePrefixLength(buf: ByteArray): Int {
            val size = buf.size
            for (k in 1..minOf(3, size)) {
                val b = buf[size - k].toInt() and 0xFF
                if (b and 0xC0 == 0x80) continue // continuation byte: look further back
                val needed = when {
                    b and 0xE0 == 0xC0 -> 2
                    b and 0xF0 == 0xE0 -> 3
                    b and 0xF8 == 0xF0 -> 4
                    else -> 1 // ASCII or an invalid lead byte: nothing to wait for
                }
                return if (needed > k) size - k else size
            }
            return size
        }
    }
}
