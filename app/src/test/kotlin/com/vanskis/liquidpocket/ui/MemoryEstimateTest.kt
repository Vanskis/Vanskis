package com.vanskis.liquidpocket.ui

import com.vanskis.liquidpocket.core.ModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryEstimateTest {
    private val lfm3 = ModelCatalog.LFM3_1_7B.copy(approxSizeBytes = 1_100_000_000L)

    @Test fun `estimate is the model file plus the KV cache per token`() {
        assertEquals(1_000L + 40L * 1024 * 8192, MemoryEstimate.bytes(1_000L, 40L * 1024, 8192))
        assertEquals(1_000L, MemoryEstimate.bytes(1_000L, 40L * 1024, 0))
    }

    @Test fun `each model gets its own per-token figure, unknown ones the cautious one`() {
        assertEquals(MemoryEstimate.LFM3_1_7B_KV_BYTES_PER_TOKEN, MemoryEstimate.kvBytesPerToken(lfm3))
        assertEquals(MemoryEstimate.LFM3_OMNI_2B_KV_BYTES_PER_TOKEN, MemoryEstimate.kvBytesPerToken(ModelCatalog.LFM3_OMNI_2B))
        assertEquals(MemoryEstimate.LFM3_OMNI_2B_KV_BYTES_PER_TOKEN, MemoryEstimate.kvBytesPerToken(lfm3.copy(displayName = "Other")))
    }

    @Test fun `memory grows with the context size`() {
        val small = MemoryEstimate.bytes(lfm3, 2048)
        val large = MemoryEstimate.bytes(lfm3, 32768)
        assertEquals(40L * 1024 * (32768 - 2048), large - small)
    }

    @Test fun `label shows decimal gigabytes and the model`() {
        // 1.1 GB + 40 KiB x 8192 = 1.1 GB + 0.34 GB
        assertEquals("≈ 1.4 GB with LFM3-1.7B", MemoryEstimate.label(lfm3, 8192))
        // 1.3 GB + 50 KiB x 32768 = 1.3 GB + 1.68 GB
        assertEquals("≈ 3.0 GB with LFM3-Omni-2B", MemoryEstimate.label(ModelCatalog.LFM3_OMNI_2B, 32768))
    }
}
