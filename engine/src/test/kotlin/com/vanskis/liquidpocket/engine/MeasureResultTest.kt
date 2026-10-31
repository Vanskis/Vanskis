package com.vanskis.liquidpocket.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasureResultTest {
    @Test
    fun `describes complete measurement with all three groups`() {
        val result = MeasureResult(
            modelName = "lfm3-1.7b",
            accelerator = Accelerator.CPU,
            modelLoadTimeMs = 350L,
            shortPrompt = TtftResult(promptTokens = 14, ttftMs = 85L, prefillTokensPerSecond = 320.5),
            longPrompt = TtftResult(promptTokens = 8000, ttftMs = 14200L, prefillTokensPerSecond = 563.4),
            chatMemory = MemorySnapshot(
                pssKb = 1_240_000L,
                nativeHeapBytes = 1_200_000_000L,
                availMemBytes = 6_500_000_000L,
                totalMemBytes = 12_000_000_000L,
                lowMemory = false,
            ),
            omniMemory = OmniMemoryResult(
                installed = true,
                snapshot = MemorySnapshot(
                    pssKb = 2_610_000L,
                    nativeHeapBytes = 2_500_000_000L,
                    availMemBytes = 5_100_000_000L,
                    totalMemBytes = 12_000_000_000L,
                    lowMemory = false,
                ),
                error = null,
                extraModelFreed = true,
            ),
            state8k = StateMeasureResult(
                tokens = 8000,
                fileSizeBytes = 33_554_432L,
                saveTimeMs = 16L,
                loadTimeMs = 19L,
                repreTimeMs = 14100L,
            ),
            state32k = StateMeasureResult(
                tokens = 32000,
                fileSizeBytes = 134_217_728L,
                saveTimeMs = 60L,
                loadTimeMs = 75L,
                repreTimeMs = 58000L,
            ),
            state32kNote = null,
        )

        val text = result.describe()

        assertTrue(text.contains("1. Model & TTFT (CPU):"))
        assertTrue(text.contains("• Model load time: 350 ms"))
        assertTrue(text.contains("• Short prompt TTFT: 85 ms (14 tokens, 320.5 tok/s)"))
        assertTrue(text.contains("• ~8K prompt TTFT: 14200 ms (8000 tokens, 563.4 tok/s)"))
        assertTrue(text.contains("2. Memory:"))
        assertTrue(text.contains("Chat model loaded:"))
        assertTrue(text.contains("PSS: 1210.9 MB"))
        assertTrue(text.contains("Native heap: 1144.4 MB"))
        assertTrue(text.contains("Avail RAM: 6198.9 MB"))
        assertTrue(text.contains("With Omni loaded alongside:"))
        assertTrue(text.contains("Extra model freed: yes"))
        assertTrue(text.contains("3. Saved chat state:"))
        assertTrue(text.contains("• 8K tokens (8000 tokens):"))
        assertTrue(text.contains("State file: 32.0 MB"))
        assertTrue(text.contains("Save: 16 ms · Load: 19 ms · Re-prefill: 14100 ms (742.1× faster)"))
        assertTrue(text.contains("• 32K tokens (32000 tokens):"))
        assertTrue(text.contains("State file: 128.0 MB"))
        assertTrue(text.contains("Save: 60 ms · Load: 75 ms · Re-prefill: 58000 ms (773.3× faster)"))
    }

    @Test
    fun `reports when Omni is not installed`() {
        val result = MeasureResult(
            modelName = "lfm3-1.7b",
            accelerator = Accelerator.NPU,
            modelLoadTimeMs = 400L,
            shortPrompt = TtftResult(promptTokens = 10, ttftMs = 50L),
            longPrompt = TtftResult(promptTokens = 7900, ttftMs = 8000L),
            chatMemory = MemorySnapshot(1_000_000L, 900_000_000L, 7_000_000_000L, 12_000_000_000L, false),
            omniMemory = OmniMemoryResult(installed = false, snapshot = null, error = null, extraModelFreed = true),
            state8k = null,
            state32k = null,
            state32kNote = "Context size 8192 too small for 32K",
        )

        val text = result.describe()

        assertTrue(text.contains("1. Model & TTFT (NPU):"))
        assertTrue(text.contains("Omni: not installed"))
        assertTrue(text.contains("Extra model freed: yes"))
        assertTrue(text.contains("8K tokens: not measured"))
        assertTrue(text.contains("32K tokens: Context size 8192 too small for 32K"))
    }

    @Test
    fun `reports Omni load failure or low memory gracefully`() {
        val result = MeasureResult(
            modelName = "lfm3-1.7b",
            accelerator = Accelerator.CPU,
            modelLoadTimeMs = 300L,
            shortPrompt = TtftResult(promptTokens = 12, ttftMs = 90L),
            longPrompt = TtftResult(promptTokens = 8000, ttftMs = 15000L),
            chatMemory = MemorySnapshot(1_000_000L, 900_000_000L, 7_000_000_000L, 12_000_000_000L, lowMemory = false),
            omniMemory = OmniMemoryResult(
                installed = true,
                snapshot = MemorySnapshot(2_800_000L, 2_700_000_000L, 500_000_000L, 12_000_000_000L, lowMemory = true),
                error = "Phone signaled low memory",
                extraModelFreed = true,
            ),
            state8k = null,
            state32k = null,
        )

        val text = result.describe()

        assertTrue(text.contains("Omni load failed: Phone signaled low memory"))
        assertTrue(text.contains("Extra model freed: yes"))
    }

    @Test
    fun `speedup ratio calculates correctly`() {
        val state = StateMeasureResult(
            tokens = 8000,
            fileSizeBytes = 32_000_000L,
            saveTimeMs = 20L,
            loadTimeMs = 25L,
            repreTimeMs = 10000L,
        )
        assertEquals(400.0, state.speedupRatio, 1e-6)

        val zeroLoad = state.copy(loadTimeMs = 0L)
        assertEquals(0.0, zeroLoad.speedupRatio, 1e-6)
    }
}
