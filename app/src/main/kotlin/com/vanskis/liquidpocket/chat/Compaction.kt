package com.vanskis.liquidpocket.chat

import com.vanskis.liquidpocket.engine.ChatMessage
import com.vanskis.liquidpocket.engine.GenerationParams
import com.vanskis.liquidpocket.engine.Role

/**
 * Compaction frees context by having the model summarize the older turns. The summary is a SYSTEM
 * [Turn] with [Turn.summary] set, placed in front of the turns kept verbatim: the conversation keeps
 * every turn (the UI shows them all), but the engine sees only the newest summary and what follows it
 * ([history]). Pure helpers; [ChatController] runs the model.
 */
object Compaction {
    /** A reply to a new user turn compacts first once the prompt fills this much of the context. */
    const val THRESHOLD_PERCENT = 85

    /** Turns after the newest summary kept verbatim, at least (the kept part starts at a user turn). */
    const val KEEP_TURNS = 4

    /** A tool result is cut to this many characters in the text the model summarizes. */
    const val MAX_TOOL_CHARS = 1000

    /** Introduces the summary to the model in the history it is sent. */
    const val SUMMARY_HEADER = "Summary of the earlier conversation:"

    /** The system prompt of the summarization request. */
    const val INSTRUCTIONS =
        "You summarize conversations. Write a short summary of the conversation you are given, in at " +
            "most 200 words: what the user wants, the key facts, names, numbers and decisions, and " +
            "anything still open. Write it as notes for continuing the conversation. Reply with the " +
            "summary only."

    /** A short summary without reasoning first. */
    val PARAMS = GenerationParams(maxNewTokens = 400, think = false)

    /** Whether [used] of [total] tokens is at least [THRESHOLD_PERCENT]; false when [total] is unknown. */
    fun shouldCompact(used: Int, total: Int): Boolean =
        total > 0 && used.toLong() * 100 >= total.toLong() * THRESHOLD_PERCENT

    /**
     * What to summarize: [summarized] (the turns after [previous], the newest summary, if any) go
     * into a new summary placed at [cut], in front of the turns kept.
     */
    data class Plan(val previous: Turn?, val summarized: List<Turn>, val cut: Int)

    /**
     * Keeps the last [KEEP_TURNS] turns, or more so the kept part starts at a turn the user wrote
     * (not in the middle of a tool round); null when that leaves nothing to summarize.
     */
    fun plan(turns: List<Turn>): Plan? {
        val previous = turns.indexOfLast { it.summary }
        val from = previous + 1
        if (turns.size - from <= KEEP_TURNS) return null
        var cut = turns.size - KEEP_TURNS
        while (cut > from && !turns[cut].writtenByUser) cut--
        if (cut <= from) return null
        return Plan(turns.getOrNull(previous), turns.subList(from, cut), cut)
    }

    /** Whether there is something to compact: more than [KEEP_TURNS] turns since the newest summary. */
    fun canCompact(turns: List<Turn>): Boolean = plan(turns) != null

    /** [turns] with the [summary] of [plan] placed at its cut. */
    fun compacted(turns: List<Turn>, plan: Plan, summary: String): List<Turn> =
        turns.take(plan.cut) + Turn(Role.SYSTEM, summary, summary = true) + turns.drop(plan.cut)

    /**
     * What the engine is sent: [system], then the newest summary as a system message, then the turns
     * after it ([toMessage] each); all of [turns] when there is no summary.
     */
    fun history(system: ChatMessage, turns: List<Turn>, toMessage: (Turn) -> ChatMessage): List<ChatMessage> {
        val newest = turns.indexOfLast { it.summary }
        val summary = turns.getOrNull(newest)?.let { ChatMessage(Role.SYSTEM, "$SUMMARY_HEADER\n${it.text}") }
        return listOfNotNull(system, summary) + turns.drop(newest + 1).map(toMessage)
    }

    /** The summarization request for [plan]: [INSTRUCTIONS], then the earlier summary and the turns as text. */
    fun request(plan: Plan): List<ChatMessage> {
        val transcript = buildString {
            plan.previous?.let { append("Summary so far:\n").append(it.text).append("\n\n") }
            append("Conversation:\n")
            plan.summarized.mapNotNull { it.transcriptLine() }.forEach { append(it).append("\n\n") }
            append("Summarize the conversation above.")
        }
        return listOf(ChatMessage(Role.SYSTEM, INSTRUCTIONS), ChatMessage(Role.USER, transcript))
    }

    private val Turn.writtenByUser: Boolean get() = role == Role.USER && !fromTool

    private fun Turn.transcriptLine(): String? = when (role) {
        Role.USER -> {
            val media = attachments.map { it.mark() } +
                if (images.isNotEmpty()) listOf("[photos]") else emptyList()
            val content = (media + text).filter { it.isNotBlank() }.joinToString(" ")
            if (fromTool) "Tool ($toolName): $content" else "User: $content"
        }
        Role.ASSISTANT -> {
            val calls = toolCalls.joinToString("") { "\n(called ${it.name} ${it.argumentsJson})" }
            if (text.isBlank() && calls.isEmpty()) null else "Assistant: $text$calls"
        }
        Role.TOOL -> "Tool ($toolName): ${text.take(MAX_TOOL_CHARS)}"
        Role.SYSTEM -> null
    }
}
