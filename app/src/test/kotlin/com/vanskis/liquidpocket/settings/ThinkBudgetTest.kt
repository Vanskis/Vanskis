package com.vanskis.liquidpocket.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ThinkBudgetTest {
    @Test fun `labels read as tokens or no limit`() {
        assertEquals(listOf("256 tokens", "512 tokens", "1K tokens", "2K tokens", "4K tokens", "8K tokens", "No limit"), ThinkBudget.STEPS.map(ThinkBudget::label))
    }

    @Test fun `stored values snap up to a slider stop`() {
        assertEquals(256, ThinkBudget.clamp(1))
        assertEquals(2048, ThinkBudget.clamp(2048))
        assertEquals(4096, ThinkBudget.clamp(3000))
        assertEquals(ThinkBudget.NO_LIMIT, ThinkBudget.clamp(100_000))
    }
}
