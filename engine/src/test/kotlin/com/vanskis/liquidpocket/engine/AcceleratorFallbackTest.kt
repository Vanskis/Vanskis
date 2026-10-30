package com.vanskis.liquidpocket.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AcceleratorFallbackTest {
    private val loads = mutableListOf<Accelerator>()
    private var inits = 0

    private fun run(
        requested: Accelerator,
        packaged: Boolean = true,
        initError: String? = null,
        npuLoadError: String? = null,
    ) = AcceleratorFallback.load(requested, packaged, { inits++; initError }) { acc ->
        loads += acc
        if (acc == Accelerator.NPU && npuLoadError != null) throw IllegalStateException(npuLoadError)
        "model on $acc"
    }

    @Test
    fun cpuRequestNeverTouchesTheNpu() {
        val out = run(Accelerator.CPU)
        assertEquals(Accelerator.CPU, out.accelerator)
        assertNull(out.note)
        assertEquals(0, inits)
        assertEquals(listOf(Accelerator.CPU), loads)
    }

    @Test
    fun npuWorks() {
        val out = run(Accelerator.NPU)
        assertEquals(Accelerator.NPU, out.accelerator)
        assertEquals("model on NPU", out.value)
        assertNull(out.note)
        assertEquals(listOf(Accelerator.NPU), loads)
    }

    @Test
    fun npuNotInBuildFallsBackWithoutStartingIt() {
        val out = run(Accelerator.NPU, packaged = false)
        assertEquals(Accelerator.CPU, out.accelerator)
        assertEquals(AcceleratorFallback.NOT_IN_BUILD, out.note)
        assertEquals(0, inits)
        assertEquals(listOf(Accelerator.CPU), loads)
    }

    @Test
    fun npuThatCrashedLastRunIsNotTouched() {
        val out = AcceleratorFallback.load(Accelerator.NPU, true, { inits++; null }, npuCrashed = true) { acc ->
            loads += acc
            "model on $acc"
        }
        assertEquals(Accelerator.CPU, out.accelerator)
        assertEquals("model on CPU", out.value)
        assertEquals("The NPU crashed last time; using the CPU", out.note)
        assertEquals(0, inits)
        assertEquals(listOf(Accelerator.CPU), loads)
    }

    @Test
    fun npuInitFailureFallsBackWithItsReason() {
        // A refused DSP session surfaces at load (npuLoadFailureRetriesOnTheCpu), not here.
        val out = run(Accelerator.NPU, initError = "the NPU backend did not start (no Hexagon DSP, or libcdsprpc.so is unavailable)")
        assertEquals(Accelerator.CPU, out.accelerator)
        assertTrue(out.note!!.contains("libcdsprpc.so is unavailable"))
        assertEquals(listOf(Accelerator.CPU), loads)
    }

    @Test
    fun npuLoadFailureRetriesOnTheCpu() {
        val out = run(Accelerator.NPU, npuLoadError = "The NPU could not load the model (the DSP refused it or ran out of memory; see the log)")
        assertEquals(Accelerator.CPU, out.accelerator)
        assertEquals("model on CPU", out.value)
        assertTrue(out.note!!.contains("The NPU could not load the model"))
        assertEquals(listOf(Accelerator.NPU, Accelerator.CPU), loads)
    }

    @Test
    fun cpuFailureAfterFallbackPropagates() {
        assertThrows(IllegalStateException::class.java) {
            AcceleratorFallback.load(Accelerator.NPU, true, { "no DSP" }) { throw IllegalStateException("corrupt GGUF") }
        }
    }
}
