package com.vanskis.liquidpocket.engine

/**
 * How many prompt tokens a reply may use in a context of [contextSize] tokens.
 *
 * [maxPromptTokens] leaves room for the reply: [GenerationParams.maxNewTokens], capped at half the
 * context so a large limit cannot make every prompt too long (generation also stops when the
 * context is full). When the conversation has to be trimmed it is trimmed further, down to
 * [targetPromptTokens], so the next few turns extend the cached prefix instead of trimming (and
 * re-processing the whole prompt) again every turn: LFM's hybrid memory cannot shift the context.
 */
internal data class ContextBudget(val maxPromptTokens: Int, val targetPromptTokens: Int) {
    companion object {
        fun of(contextSize: Int, maxNewTokens: Int): ContextBudget {
            val reserve = maxNewTokens.coerceIn(1, contextSize / 2)
            val max = contextSize - reserve
            return ContextBudget(max, max - contextSize / 4)
        }
    }
}

internal data class TrimResult(val messages: List<ChatMessage>, val promptTokens: Int)

/** Drops the oldest turns of a conversation that does not fit its [ContextBudget]. */
internal object ConversationTrimmer {
    /**
     * Returns [messages] unchanged if they fit [ContextBudget.maxPromptTokens] as measured by
     * [countTokens]. Otherwise drops the oldest non-system messages (never the last user message or
     * anything after it, such as the tool calls and results of the current turn, and never leaving
     * an assistant reply or tool result first in the history) until the prompt fits
     * [ContextBudget.targetPromptTokens] or nothing more can be dropped. Throws [EngineException]
     * if even the shortest conversation exceeds [ContextBudget.maxPromptTokens].
     */
    fun trim(
        messages: List<ChatMessage>,
        budget: ContextBudget,
        countTokens: (List<ChatMessage>) -> Int,
    ): TrimResult {
        var count = countTokens(messages)
        if (count <= budget.maxPromptTokens) return TrimResult(messages, count)

        val kept = messages.toMutableList()
        while (count > budget.targetPromptTokens) {
            if (!dropOldestTurn(kept)) break
            count = countTokens(kept)
        }
        if (count > budget.maxPromptTokens) {
            throw EngineException(
                "The message is too long: the prompt needs $count tokens but at most " +
                    "${budget.maxPromptTokens} fit. Shorten it or raise the context size.",
            )
        }
        return TrimResult(kept, count)
    }

    /** Removes the oldest droppable turn (a message plus any assistant replies and tool results after it). */
    private fun dropOldestTurn(kept: MutableList<ChatMessage>): Boolean {
        val protectedFrom = kept.indexOfLast { it.role == Role.USER }.coerceAtLeast(0)
        val first = kept.indices.firstOrNull { it < protectedFrom && kept[it].role != Role.SYSTEM }
            ?: return false
        kept.removeAt(first)
        while (true) {
            // Stops at the protected user message at the latest.
            val next = kept.indices.firstOrNull { kept[it].role != Role.SYSTEM } ?: break
            if (kept[next].role != Role.ASSISTANT && kept[next].role != Role.TOOL) break
            kept.removeAt(next)
        }
        return true
    }
}
