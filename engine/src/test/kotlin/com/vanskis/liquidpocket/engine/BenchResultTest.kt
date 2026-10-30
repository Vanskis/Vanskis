package com.vanskis.liquidpocket.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchResultTest {
    @Test fun `a speed within noise of the CPU is called the same`() {
        assertEquals("About the same: the NPU is not speeding this model up.", BenchResult(20.0, 21.0).verdict)
        assertEquals("About the same: the NPU is not speeding this model up.", BenchResult(20.0, 18.0).verdict)
    }

    @Test fun `a clear difference is named with its ratio`() {
        assertEquals("The NPU is 1.5× faster than the CPU.", BenchResult(20.0, 30.0).verdict)
        assertEquals("The NPU is slower than the CPU (0.5×).", BenchResult(20.0, 10.0).verdict)
    }

    @Test fun `no NPU run has no verdict`() {
        assertNull(BenchResult(20.0, null, "No NPU in this build").verdict)
        assertNull(BenchResult(0.0, 10.0).verdict)
    }
}

class NpuPickTest {
    @Test fun `the NPU is picked only where it wins`() {
        assertTrue(BenchResult(20.0, 30.0).npuWins)
        assertFalse(BenchResult(20.0, 21.0).npuWins)
        assertFalse(BenchResult(20.0, null, "No NPU in this build").npuWins)
        // Prefill much faster, decode about the same: a win.
        assertTrue(BenchResult(20.0, 19.0, cpuPrefillTokPerSec = 100.0, npuPrefillTokPerSec = 300.0).npuWins)
        // Prefill faster but decode clearly slower: not a win.
        assertFalse(BenchResult(20.0, 15.0, cpuPrefillTokPerSec = 100.0, npuPrefillTokPerSec = 300.0).npuWins)
        // Faster but drains clearly more battery per reply: not a win.
        assertFalse(BenchResult(20.0, 30.0, cpuMahPerReply = 1.0, npuMahPerReply = 2.0).npuWins)
        assertTrue(BenchResult(20.0, 30.0, cpuMahPerReply = 1.0, npuMahPerReply = 0.8).npuWins)
    }

    @Test fun `drain is the drop between charge readings`() {
        assertEquals(1.5, BenchResult.drainMah(10_000L, 8_500L)!!, 1e-9)
        assertNull(BenchResult.drainMah(10_000L, 10_000L))
        assertNull(BenchResult.drainMah(null, 8_500L))
    }
}
