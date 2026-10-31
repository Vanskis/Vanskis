package com.vanskis.liquidpocket.engine

/**
 * The separate parts of a finished reply as parsed by the chat template's parser (see
 * [LlamaNative.nativeParseReply]): reasoning, answer text, and any tool calls.
 */
internal data class ParsedReply(
    val thinking: String,
    val text: String,
    val toolCalls: List<ToolCall>,
) {
    companion object {
        fun of(raw: List<String>?): ParsedReply? {
            if (raw == null || raw.size < 2) return null
            val thinking = raw[0]
            val text = raw[1]
            val toolCalls = raw.drop(2).chunked(2).map { (name, args) ->
                ToolCall(name, args)
            }
            return ParsedReply(thinking, text, toolCalls)
        }
    }
}

/**
 * Handles the streamed tokens and final parts of a reply.
 *
 * When the chat template supports it, thinking, answer text, and tool calls come directly from
 * llama.cpp's chat parser ([ParsedReply]) as separate parts. When the template or parser does
 * not handle it (or parsing fails), [ReplyFilter] is used as the fallback marker scanner.
 */
internal class ReplyParser(
    private val filter: ReplyFilter? = null,
) {
    /** True while the streamed reply is inside a think block. */
    val thinking: Boolean get() = filter?.thinking == true

    private val shownThinking = StringBuilder()
    private val shownText = StringBuilder()
    private val raw = StringBuilder()

    /**
     * Appends the next piece of streamed text and returns any [ReplyChunk]s ready to show.
     */
    fun append(piece: String): List<ReplyChunk> {
        raw.append(piece)
        if (filter != null) {
            val shown = filter.append(piece)
            val chunks = mutableListOf<ReplyChunk>()
            if (shown.thinking.isNotEmpty()) {
                shownThinking.append(shown.thinking)
                chunks += ReplyChunk.Thinking(shown.thinking)
            }
            if (shown.text.isNotEmpty()) {
                shownText.append(shown.text)
                chunks += ReplyChunk.Text(shown.text)
            }
            return chunks
        }
        return emptyList()
    }

    /**
     * Completes the reply once generation stops.
     *
     * With [parsed] from the chat parser, takes thinking, answer text, and tool calls as separate
     * parts. Otherwise falls back to [filter] marker scanning.
     */
    fun complete(parsed: ParsedReply?): List<ReplyChunk> {
        val chunks = mutableListOf<ReplyChunk>()
        if (parsed != null) {
            val shownT = shownThinking.toString()
            val restThinking = if (parsed.thinking.startsWith(shownT)) {
                parsed.thinking.substring(shownT.length)
            } else if (shownT.isEmpty()) {
                parsed.thinking
            } else {
                ""
            }
            if (restThinking.isNotEmpty()) {
                chunks += ReplyChunk.Thinking(restThinking)
            }

            val shown = shownText.toString()
            val restText = if (parsed.text.startsWith(shown)) {
                parsed.text.substring(shown.length)
            } else if (shown.isEmpty()) {
                parsed.text
            } else {
                ""
            }
            if (restText.isNotEmpty()) {
                chunks += ReplyChunk.Text(restText)
            }

            for (call in parsed.toolCalls) {
                chunks += ReplyChunk.ToolCall(call)
            }
        } else if (filter != null) {
            val rest = filter.complete(null)
            if (rest.thinking.isNotEmpty()) chunks += ReplyChunk.Thinking(rest.thinking)
            if (rest.text.isNotEmpty()) chunks += ReplyChunk.Text(rest.text)
        } else {
            val text = raw.toString().trim()
            if (text.isNotEmpty()) chunks += ReplyChunk.Text(text)
        }
        return chunks
    }
}
