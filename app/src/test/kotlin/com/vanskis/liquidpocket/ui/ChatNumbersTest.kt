package com.vanskis.liquidpocket.ui

import com.vanskis.liquidpocket.chat.Turn
import com.vanskis.liquidpocket.engine.GenerationStats
import com.vanskis.liquidpocket.engine.Role
import com.vanskis.liquidpocket.ui.components.ContextLevel
import com.vanskis.liquidpocket.ui.components.Indicators
import com.vanskis.liquidpocket.ui.components.contextFraction
import com.vanskis.liquidpocket.ui.components.contextLevel
import com.vanskis.liquidpocket.ui.components.contextTokensUsed
import com.vanskis.liquidpocket.ui.components.formatTokenCount
import com.vanskis.liquidpocket.ui.components.liveAnswerLabel
import com.vanskis.liquidpocket.ui.components.statsSummary
import com.vanskis.liquidpocket.ui.components.thinkingLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatNumbersTest {
    @Test fun `ring turns amber at 80 percent and red at 95 percent`() {
        assertEquals(ContextLevel.NORMAL, contextLevel(0, 8192))
        assertEquals(ContextLevel.NORMAL, contextLevel(6553, 8192)) // 79.99%
        assertEquals(ContextLevel.WARNING, contextLevel(6554, 8192)) // 80.0%
        assertEquals(ContextLevel.WARNING, contextLevel(7782, 8192)) // 94.99%
        assertEquals(ContextLevel.CRITICAL, contextLevel(7783, 8192)) // 95.0%
        assertEquals(ContextLevel.CRITICAL, contextLevel(9000, 8192))
    }

    @Test fun `exact thresholds and an unknown total`() {
        assertEquals(ContextLevel.WARNING, contextLevel(80, 100))
        assertEquals(ContextLevel.CRITICAL, contextLevel(95, 100))
        assertEquals(ContextLevel.NORMAL, contextLevel(500, 0))
        // Large counts do not overflow the percentage math.
        assertEquals(ContextLevel.WARNING, contextLevel(Int.MAX_VALUE / 10 * 8, Int.MAX_VALUE / 10 * 9))
    }

    @Test fun `fraction is clamped and zero without a total`() {
        assertEquals(0.5f, contextFraction(4096, 8192), 0f)
        assertEquals(1f, contextFraction(9000, 8192), 0f)
        assertEquals(0f, contextFraction(100, 0), 0f)
    }

    @Test fun `token counts read like context sizes`() {
        assertEquals("512", formatTokenCount(512))
        assertEquals("1K", formatTokenCount(1024))
        assertEquals("3.1K", formatTokenCount(3174))
        assertEquals("8K", formatTokenCount(8192))
        assertEquals("12K", formatTokenCount(12_000))
        assertEquals("32K", formatTokenCount(32768))
        assertEquals("0", formatTokenCount(-5))
    }

    @Test fun `context used comes from the newest reply with stats`() {
        val older = GenerationStats(promptTokens = 100, prefillTokensPerSecond = 50.0, generatedTokens = 20, decodeTokensPerSecond = 10.0)
        val newer = GenerationStats(promptTokens = 900, prefillTokensPerSecond = 50.0, generatedTokens = 300, decodeTokensPerSecond = 10.0)
        val turns = listOf(
            Turn(Role.USER, "hi"),
            Turn(Role.ASSISTANT, "hello", stats = older),
            Turn(Role.USER, "more"),
            Turn(Role.ASSISTANT, "sure", stats = newer),
            Turn(Role.USER, "and?"),
        )
        assertEquals(1200, contextTokensUsed(turns))
        assertEquals(0, contextTokensUsed(listOf(Turn(Role.USER, "hi"))))
        assertEquals(0, contextTokensUsed(emptyList()))
    }

    @Test fun `stats line shows speed and time to first token`() {
        val stats = GenerationStats(promptTokens = 200, prefillTokensPerSecond = 500.0, generatedTokens = 120, decodeTokensPerSecond = 23.4)
        assertEquals("23 tok/s · 0.4 s to first token", statsSummary(stats, Indicators()))
        assertEquals("23 tok/s", statsSummary(stats.copy(prefillTokensPerSecond = 0.0), Indicators()))
        assertEquals("23 tok/s · 0.4 s to first token · 120 tokens", statsSummary(stats))
    }

    @Test fun `thinking header follows the stream`() {
        assertEquals("Thinking…", thinkingLabel(null, streaming = true))
        assertEquals("Thought for 12s", thinkingLabel(12, streaming = false))
        assertEquals("Thoughts", thinkingLabel(null, streaming = false))
        assertEquals("Thinking · 12s · 28 tok/s · 340 tokens", thinkingLabel(12, streaming = true, tokens = 340))
        assertEquals("Thinking · 0s · 1 token", thinkingLabel(null, streaming = true, tokens = 1))
        assertEquals("Thought for 12s · 28 tok/s · 340 tokens", thinkingLabel(12, streaming = false, tokens = 340))
        assertEquals("Thought for 3s", thinkingLabel(3, streaming = false, tokens = 0))
    }

    @Test
    fun indicatorsTurnOffTheirParts() {
        val defaults = Indicators()
        assertEquals("Thinking · 12s · 28 tok/s", thinkingLabel(12, streaming = true, tokens = 340, show = defaults))
        assertEquals("Thoughts · 28 tok/s", thinkingLabel(12, streaming = false, tokens = 340, show = defaults.copy(elapsed = false)))
        assertEquals("Thinking…", thinkingLabel(12, streaming = true, tokens = 340, show = Indicators(false, false, false, false)))
        val stats = GenerationStats(promptTokens = 0, prefillTokensPerSecond = 0.0, generatedTokens = 200, decodeTokensPerSecond = 23.0, thinkingTokens = 20)
        assertEquals("23 tok/s", statsSummary(stats, defaults))
        assertEquals("180 tokens", statsSummary(stats, Indicators(answerSpeed = false, tokenCount = true)))
        assertEquals(null, statsSummary(stats, Indicators(answerSpeed = false)))
        assertEquals(null, liveAnswerLabel(10, 900))
        assertEquals("Writing · 20 tok/s", liveAnswerLabel(30, 1500))
    }
}
