package com.vanskis.liquidpocket.chat

const val SYSTEM_PROMPT =
    "You are Liquid Pocket, a helpful assistant running fully offline on the user's phone. Be concise."

/**
 * The system prompt: [SYSTEM_PROMPT] followed by the user's remembered facts as a short bulleted
 * list. Facts are taken oldest first while their total length stays within [FACTS_BUDGET_CHARS], so
 * a long list cannot crowd the conversation out of a small context.
 */
object SystemPrompt {
    /** Longest single fact the Things to remember screen accepts. */
    const val MAX_FACT_CHARS = 200

    /** Total characters of fact text given to the model. */
    const val FACTS_BUDGET_CHARS = 1000

    const val FACTS_HEADER = "Things the user asked you to remember:"

    /** [facts] as they go into the prompt: one line each, blank ones dropped, each at most [MAX_FACT_CHARS]. */
    fun normalize(facts: List<String>): List<String> =
        facts.map { it.replace(WHITESPACE, " ").trim().take(MAX_FACT_CHARS) }.filter { it.isNotEmpty() }

    /** How many of [facts] (oldest first, as given) fit in the budget; the rest are left out. */
    fun fittingCount(facts: List<String>): Int {
        var used = 0
        facts.forEachIndexed { i, raw ->
            val length = normalize(listOf(raw)).firstOrNull()?.length ?: 0
            if (used + length > FACTS_BUDGET_CHARS) return i
            used += length
        }
        return facts.size
    }

    /** Said when tools are offered. */
    const val TOOLS_LINE =
        "You can use tools on the user's phone. Use a tool for the current date and time rather than guessing. " +
            "Actions that change something (events, reminders, notes, alarms, memories) are done only after the user approves them."

    fun build(facts: List<String>, tools: Boolean = false): String {
        val base = if (tools) "$SYSTEM_PROMPT\n$TOOLS_LINE" else SYSTEM_PROMPT
        val used = normalize(facts.take(fittingCount(facts)))
        if (used.isEmpty()) return base
        return base + "\n\n" + FACTS_HEADER + "\n" + used.joinToString("\n") { "- $it" }
    }

    private val WHITESPACE = Regex("\\s+")
}
