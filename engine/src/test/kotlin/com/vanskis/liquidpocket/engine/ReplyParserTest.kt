package com.vanskis.liquidpocket.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class ReplyParserTest {
    @Test
    fun streamedReplyWithThinkingTextAndToolCallProducesThreeSeparateParts() {
        val filter = ReplyFilter(
            thinkStart = "<think>",
            thinkEnds = listOf("</think>", "<tool_call>"),
            toolStarts = listOf("<tool_call>"),
            startInThink = false,
        )
        val parser = ReplyParser(filter)

        // Stream pieces: thinking, then answer text, then tool-call markup
        val chunks = mutableListOf<ReplyChunk>()
        chunks += parser.append("<think>Need to check the weather</think>")
        chunks += parser.append("Here is the current forecast:\n")
        chunks += parser.append("<tool_call><function=get_weather>{\"city\":\"Paris\"}</tool_call>")

        // Completed with template parser output (common_chat_parse via g_parser):
        // separate parts for reasoning_content, content, and tool_calls
        val parsed = ParsedReply(
            thinking = "Need to check the weather",
            text = "Here is the current forecast:",
            toolCalls = listOf(ToolCall("get_weather", "{\"city\":\"Paris\"}")),
        )
        chunks += parser.complete(parsed)

        assertEquals(3, chunks.size)
        assertEquals(ReplyChunk.Thinking("Need to check the weather"), chunks[0])
        assertEquals(ReplyChunk.Text("Here is the current forecast:"), chunks[1])
        assertEquals(ReplyChunk.ToolCall(ToolCall("get_weather", "{\"city\":\"Paris\"}")), chunks[2])
    }

    @Test
    fun fallbackToReplyFilterWhenTemplateParserReturnsNull() {
        val filter = ReplyFilter(
            thinkStart = "<think>",
            thinkEnds = listOf("</think>"),
            toolStarts = emptyList(),
            startInThink = false,
        )
        val parser = ReplyParser(filter)

        val chunks = mutableListOf<ReplyChunk>()
        chunks += parser.append("<think>Thinking</think>Hello")
        chunks += parser.complete(null)

        assertEquals(2, chunks.size)
        assertEquals(ReplyChunk.Thinking("Thinking"), chunks[0])
        assertEquals(ReplyChunk.Text("Hello"), chunks[1])
    }

    @Test
    fun parsedReplyOfExtractsThinkingContentAndToolCalls() {
        val raw = listOf(
            "thinking about math",
            "The result is 42.",
            "calculate",
            "{\"expr\":\"6*7\"}",
        )
        val parsed = ParsedReply.of(raw)
        assertEquals("thinking about math", parsed?.thinking)
        assertEquals("The result is 42.", parsed?.text)
        assertEquals(listOf(ToolCall("calculate", "{\"expr\":\"6*7\"}")), parsed?.toolCalls)
    }

    @Test
    fun parsedReplyOfWithoutToolCalls() {
        val raw = listOf("reasoning", "just text")
        val parsed = ParsedReply.of(raw)
        assertEquals("reasoning", parsed?.thinking)
        assertEquals("just text", parsed?.text)
        assertEquals(emptyList<ToolCall>(), parsed?.toolCalls)
    }
}
