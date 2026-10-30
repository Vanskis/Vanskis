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

/** Which numbers the chat shows about a reply; each is a switch in Settings. */
data class Indicators(
    /** Tokens a second while it thinks, and after. */
    val thinkingSpeed: Boolean = true,
    /** Tokens a second while it answers, and the speed and time to first token under the reply. */
    val answerSpeed: Boolean = true,
    /** How many tokens it thought and answered. */
    val tokenCount: Boolean = false,
    /** How long it thought. */
    val elapsed: Boolean = true,
) {
    companion object {
        val ALL = Indicators(thinkingSpeed = true, answerSpeed = true, tokenCount = true, elapsed = true)
    }
}

/**
 * "23 tok/s · 0.4 s to first token · 180 tokens" as [show] allows: the speed and time to first
 * token (when the prefill speed is known) with [Indicators.answerSpeed], the answer's tokens with
 * [Indicators.tokenCount]. Null when nothing is to be shown.
 */
fun statsSummary(stats: GenerationStats, show: Indicators = Indicators.ALL): String? {
    val parts = mutableListOf<String>()
    if (show.answerSpeed) {
        parts += String.format(Locale.ROOT, "%.0f tok/s", stats.decodeTokensPerSecond)
        if (stats.prefillTokensPerSecond > 0.0 && stats.promptTokens > 0) {
            parts += String.format(Locale.ROOT, "%.1f s to first token", stats.promptTokens / stats.prefillTokensPerSecond)
        }
    }
    val answered = stats.generatedTokens - stats.thinkingTokens
    if (show.tokenCount && answered > 0) parts += tokenCount(answered)
    return parts.joinToString(" · ").ifEmpty { null }
}

/** "Writing · 21 tok/s" under a reply that is streaming, once a second of it has passed. */
fun liveAnswerLabel(tokens: Int, millis: Long): String? =
    if (tokens <= 0 || millis < 1000) null else String.format(Locale.ROOT, "Writing · %.0f tok/s", tokens * 1000.0 / millis)

private fun tokenCount(n: Int) = if (n == 1) "1 token" else "$n tokens"

/**
 * The thinking section's header, as [show] allows: "Thinking · 12s · 28 tok/s · 340 tokens" while
 * it streams (just "Thinking…" before anything is known), then "Thought for 12s · …" (or "Thoughts").
 * The speed needs a second of thinking.
 */
fun thinkingLabel(seconds: Int?, streaming: Boolean, tokens: Int? = null, show: Indicators = Indicators.ALL): String {
    val time = seconds?.takeIf { it >= 0 }
    val thought = tokens?.takeIf { it > 0 }
    val speed = if (show.thinkingSpeed && thought != null && time != null && time > 0) "${thought / time} tok/s" else null
    val count = if (show.tokenCount) thought?.let(::tokenCount) else null
    val head = when {
        streaming -> if ((time == null && thought == null) || !show.elapsed) "Thinking" else "Thinking · ${time ?: 0}s"
        time != null && show.elapsed -> "Thought for ${time}s"
        else -> "Thoughts"
    }
    val parts = listOfNotNull(head, speed, count)
    return if (streaming && parts.size == 1) "Thinking…" else parts.joinToString(" · ")
}
