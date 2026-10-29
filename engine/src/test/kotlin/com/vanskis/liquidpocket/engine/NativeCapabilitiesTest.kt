package com.vanskis.liquidpocket.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NativeCapabilitiesTest {
    @Test
    fun noProjectorIsNone() {
        assertEquals(Capabilities.NONE, NativeCapabilities.of(intArrayOf(0, 0, 0, 0, 0)))
    }

    @Test
    fun speechOutputComesFromTheNativeFlags() {
        val caps = NativeCapabilities.of(intArrayOf(0, 1, 16000, 1, 24000))
        assertEquals(Capabilities(vision = false, audioIn = true, audioOut = true, 16000, 24000), caps)
    }

    @Test
    fun visionWithoutAudio() {
        val caps = NativeCapabilities.of(intArrayOf(1, 0, 0, 0, 0))
        assertEquals(Capabilities(vision = true, audioIn = false, audioOut = false, 0, 0), caps)
    }

    @Test
    fun speechOutputWithoutSampleRateIsOff() {
        val caps = NativeCapabilities.of(intArrayOf(0, 0, 0, 1, 0))
        assertEquals(Capabilities.NONE, caps)
    }

    @Test
    fun rejectsTheOldThreeValueLayout() {
        assertThrows(IllegalArgumentException::class.java) { NativeCapabilities.of(intArrayOf(1, 1, 16000)) }
    }
}
