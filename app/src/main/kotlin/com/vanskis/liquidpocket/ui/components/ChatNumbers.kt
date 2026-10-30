package com.vanskis.liquidpocket.ui.components

import com.vanskis.liquidpocket.chat.Turn
import com.vanskis.liquidpocket.engine.GenerationStats
import java.util.Locale

/** How full the context window is, which colors the composer's ring. */
enum class ContextLevel { NORMAL, WARNING, CRITICAL }

/** Amber from 80% full, red from 95%; [NORMAL] when [total] is unknown (0 or less). */
fun contextLevel(used: Int, total: Int): ContextLevel = when {
    total <= 0 -> ContextLevel.NORMAL
    used.toLong() * 100 >= total.toLong() * 95 -> ContextLevel.CRITICAL
    used.toLong() * 100 >= total.toLong() * 80 -> ContextLevel.WARNING
    else -> ContextLevel.NORMAL
}

/** [used] / [total] in 0..1; 0 when [total] is unknown. */
fun contextFraction(used: Int, total: Int): Float =
    if (total <= 0) 0f else (used.toFloat() / total).coerceIn(0f, 1f)

/** Token counts in K of 1024, as the context sizes are: 512, 3.1K, 8K, 32K. */
fun formatTokenCount(tokens: Int): String {
    if (tokens < 1024) return tokens.coerceAtLeast(0).toString()
    val k = tokens / 1024.0
    val tenths = Math.round(k * 10)
    return if (k >= 10 || tenths % 10 == 0L) "${Math.round(k)}K" else String.format(Locale.ROOT, "%.1fK", tenths / 10.0)
}

/**
 * Tokens held in the context after the newest reply that has stats: its prompt plus what it
 * generated. 0 when no reply has stats yet (a new chat). The estimate [com.vanskis.liquidpocket.chat.ChatState.contextUsed]
 * falls back to when the engine cannot count tokens; the next prompt adds the user's new message on top.
 */
fun contextTokensUsed(turns: List<Turn>): Int {
    val stats = turns.lastOrNull { it.stats != null }?.stats ?: return 0
    return stats.promptTokens + stats.generatedTokens
}

/** "23 tok/s · 0.4 s to first token"; the second part only when the prefill speed is known. */
fun statsSummary(stats: GenerationStats): String {
    val decode = String.format(Locale.ROOT, "%.0f tok/s", stats.decodeTokensPerSecond)
    if (stats.prefillTokensPerSecond <= 0.0 || stats.promptTokens <= 0) return decode
    val firstToken = stats.promptTokens / stats.prefillTokensPerSecond
    return decode + String.format(Locale.ROOT, " · %.1f s to first token", firstToken)
}

/**
 * The thinking section's header: "Thinking · 12s · 340 tokens" while it streams (just "Thinking…"
 * before either is known), then "Thought for 12s · 340 tokens" (or "Thoughts").
 */
fun thinkingLabel(seconds: Int?, streaming: Boolean, tokens: Int? = null): String {
    val time = seconds?.takeIf { it >= 0 }
    val count = tokens?.takeIf { it > 0 }?.let { if (it == 1) "1 token" else "$it tokens" }
    val head = when {
        streaming -> if (time == null && count == null) return "Thinking…" else "Thinking · ${time ?: 0}s"
        time != null -> "Thought for ${time}s"
        else -> "Thoughts"
    }
    return if (count == null) head else "$head · $count"
}
