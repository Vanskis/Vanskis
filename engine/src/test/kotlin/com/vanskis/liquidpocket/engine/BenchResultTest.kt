package com.vanskis.liquidpocket.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
