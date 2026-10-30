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

    /** Said when the tools include web search. */
    const val WEB_SEARCH_LINE = "For current events, or facts you are unsure of, use web_search."

    /** Longest custom instruction text the Settings field accepts. */
    const val MAX_CUSTOM_CHARS = 1000

    const val CUSTOM_HEADER = "Custom instructions from the user:"

    /**
     * [custom] is the user's free-text instructions from Settings, added after the facts; blank adds
     * nothing and anything past [MAX_CUSTOM_CHARS] is cut. [webSearch] (with [tools]) adds [WEB_SEARCH_LINE].
     */
    fun build(facts: List<String>, tools: Boolean = false, custom: String = "", webSearch: Boolean = false): String {
        val toolLines = if (webSearch) "$TOOLS_LINE\n$WEB_SEARCH_LINE" else TOOLS_LINE
        val base = if (tools) "$SYSTEM_PROMPT\n$toolLines" else SYSTEM_PROMPT
        val used = normalize(facts.take(fittingCount(facts)))
        val withFacts =
            if (used.isEmpty()) base else base + "\n\n" + FACTS_HEADER + "\n" + used.joinToString("\n") { "- $it" }
        val extra = custom.trim().take(MAX_CUSTOM_CHARS).trim()
        return if (extra.isEmpty()) withFacts else withFacts + "\n\n" + CUSTOM_HEADER + "\n" + extra
    }

    private val WHITESPACE = Regex("\\s+")
}

/** The preset chips in Settings: each fills the custom instructions field with its text. */
object InstructionPresets {
    val ALL: List<Pair<String, String>> = listOf(
        "Concise" to "Keep answers short and to the point. Skip preambles and don't repeat the question.",
        "Explain like I'm 12" to "Explain things in simple words a 12-year-old would understand. Use everyday examples and avoid jargon.",
        "Friendly" to "Be warm, upbeat and conversational, like a friendly helper. Encourage the user and keep the tone light.",
        "Formal" to "Use a formal, professional tone. Write in complete sentences, with no slang or emoji.",
        "Code helper" to "Act as a programming assistant. Give working code in fenced code blocks, then a brief explanation of how it works.",
    )
}
