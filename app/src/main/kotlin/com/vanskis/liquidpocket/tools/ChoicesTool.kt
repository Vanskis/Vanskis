package com.vanskis.liquidpocket.tools

import com.vanskis.liquidpocket.engine.ToolCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

/**
 * offer_choices: the model asks the user a question with options to tap. The options show as
 * chips under the reply, a tap sends the option as the user's next message, and the call ends the
 * model's turn ([com.vanskis.liquidpocket.chat.ChatController] runs no further round after it).
 */
class OfferChoicesTool : JsonTool(
    name = Choices.TOOL,
    description = "Ask the user a question with 2 to ${Choices.MAX_OPTIONS} short answers to tap, for example the paths in an " +
        "interactive story, a quiz, or a question that has a few clear answers. Ends your turn: the user's pick " +
        "arrives as their next message, so do not answer for them.",
    parametersJson = """
        {"type": "object", "properties": {
          "question": {"type": "string", "description": "The question, in one sentence."},
          "options": {"type": "array", "items": {"type": "string"}, "description": "2 to ${Choices.MAX_OPTIONS} answers, each a few words."}
        }, "required": ["question", "options"]}
    """,
    needsConfirmation = false,
) {
    override fun check(args: JsonObject): String? =
        if (Choices.offer(args) == null) "Give a question and 2 to ${Choices.MAX_OPTIONS} different options." else null

    override suspend fun run(args: JsonObject) = ToolResults.ok {
        put("status", "shown")
        put("message", "The user sees the choices; their pick (or their own answer) comes as the next message.")
    }
}

object Choices {
    const val TOOL = "offer_choices"
    const val MAX_OPTIONS = 6

    data class Offer(val question: String, val options: List<String>) {
        /** What is spoken for a voice reply: its [replyText], the question, then the options in order. */
        fun spoken(replyText: String): String = buildString {
            if (replyText.isNotBlank()) append(replyText.trim()).append("\n\n")
            if (question !in replyText) append(question).append(' ')
            append(options.mapIndexed { i, option -> "${i + 1}: $option." }.joinToString(" "))
        }
    }

    fun offer(args: JsonObject): Offer? {
        val question = args.string("question") ?: return null
        val options = args.strings("options").distinct().take(MAX_OPTIONS)
        return if (options.size < 2) null else Offer(question, options)
    }

    /** The valid offer among [calls], if the reply made one. */
    fun offerIn(calls: List<ToolCall>): Offer? =
        calls.firstNotNullOfOrNull { if (it.name == TOOL) offer(ToolArgs.parse(it.argumentsJson)) else null }
}
