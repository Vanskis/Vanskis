package com.vanskis.liquidpocket.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ContextSizeAndShaTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun clamp() {
        assertEquals(2048, ContextSize.clamp(100))
        assertEquals(262144, ContextSize.clamp(1_000_000))
        assertEquals(8192, ContextSize.clamp(8192, trainedCtx = 128_000))
        assertEquals(4096, ContextSize.clamp(8192, trainedCtx = 4096))
        assertEquals(16384, ContextSize.clamp(16384, trainedCtx = 0))
        assertTrue(ContextSize.DEFAULT in ContextSize.OPTIONS)
    }

    @Test fun optionsRunPastTheWarningToTheMax() {
        assertEquals(ContextSize.MIN, ContextSize.OPTIONS.first())
        assertEquals(ContextSize.MAX, ContextSize.OPTIONS.last())
        assertEquals(ContextSize.OPTIONS.sorted(), ContextSize.OPTIONS)
        assertEquals(ContextSize.OPTIONS.indexOf(131072), ContextSize.nearestOption(120_000))
        assertEquals(ContextSize.OPTIONS.indexOf(8192), ContextSize.nearestOption(8192))
    }

    @Test fun sha256KnownVector() {
        val f = tmp.newFile().apply { writeText("abc") }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.of(f))
        assertTrue(Sha256.matches(f, "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD"))
    }
}
