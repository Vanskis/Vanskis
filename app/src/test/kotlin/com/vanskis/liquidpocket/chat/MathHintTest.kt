package com.vanskis.liquidpocket.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MathHintTest {
    @Test fun `operations between numbers want math`() {
        listOf("what is 12*7", "3.5 + 2?", "2^10", "100 / (4)", "4000 × 2.0").forEach { assertTrue(it, MathHint.wanted(it)) }
    }

    @Test fun `math words want math only with a number`() {
        assertTrue(MathHint.wanted("what's 15 percent of 80"))
        assertTrue(MathHint.wanted("convert 5 miles to km"))
        assertTrue(MathHint.wanted("tip of 18% on 42 dollars"))
        assertFalse(MathHint.wanted("how much do you know about Rome?"))
    }

    @Test fun `plain messages and dates do not`() {
        listOf("hello", "meet at 10 tomorrow", "call mom on 2026-05-01", "I have 3 cats").forEach { assertFalse(it, MathHint.wanted(it)) }
    }
}
