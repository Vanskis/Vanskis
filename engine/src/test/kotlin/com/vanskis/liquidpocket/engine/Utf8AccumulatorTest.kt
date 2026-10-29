package com.vanskis.liquidpocket.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class Utf8AccumulatorTest {
    @Test
    fun asciiPassesThrough() {
        val acc = Utf8Accumulator()
        assertEquals("Hello", acc.append("Hello".toByteArray()))
        assertEquals(" world", acc.append(" world".toByteArray()))
    }

    @Test
    fun emojiSplitAcrossPiecesIsEmittedWhole() {
        val emoji = "😀".toByteArray(Charsets.UTF_8) // U+1F600, 4 bytes
        val acc = Utf8Accumulator()
        assertEquals("ok ", acc.append("ok ".toByteArray() + emoji.copyOfRange(0, 1)))
        assertEquals("", acc.append(emoji.copyOfRange(1, 3)))
        assertEquals("😀!", acc.append(emoji.copyOfRange(3, 4) + "!".toByteArray()))
    }

    @Test
    fun twoAndThreeByteCharactersSplitInTheMiddle() {
        val text = "é€"
        val bytes = text.toByteArray(Charsets.UTF_8) // C3 A9 E2 82 AC
        val acc = Utf8Accumulator()
        val out = StringBuilder()
        for (b in bytes) out.append(acc.append(byteArrayOf(b)))
        assertEquals(text, out.toString())
    }

    @Test
    fun completePrefixLength() {
        assertEquals(0, Utf8Accumulator.completePrefixLength(byteArrayOf()))
        assertEquals(2, Utf8Accumulator.completePrefixLength("ab".toByteArray()))
        assertEquals(1, Utf8Accumulator.completePrefixLength(byteArrayOf(0x61, 0xE2.toByte(), 0x82.toByte())))
        assertEquals(3, Utf8Accumulator.completePrefixLength(byteArrayOf(0xE2.toByte(), 0x82.toByte(), 0xAC.toByte())))
        // Stray continuation bytes are not waited on; they decode to U+FFFD.
        assertEquals(4, Utf8Accumulator.completePrefixLength(ByteArray(4) { 0x80.toByte() }))
    }
}
