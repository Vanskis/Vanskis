package com.vanskis.liquidpocket.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class ReplyFilterTest {
    private val tool = "<|tool_call_start|>"

    private fun filter() = ReplyFilter("<think>", listOf("</think>"), listOf(tool))

    /** Feeds [pieces] one by one and returns the text each call showed. */
    private fun ReplyFilter.feed(vararg pieces: String) = pieces.map { append(it).text }

    /** Feeds [pieces] one by one and returns each call's (thinking, text). */
    private fun ReplyFilter.feedBoth(vararg pieces: String) = pieces.map { append(it).let { s -> s.thinking to s.text } }

    /** LFM3 as the template's parser describes it (common_chat_params_init_qwen3_coder). */
    private fun lfm3(startInThink: Boolean) = ReplyFilter(
        "<think>", listOf("\n</think>", "</think>", "<tool_call>"), listOf("<tool_call>"), startInThink,
    )

    @Test
    fun plainTextStreamsHoldingBackTrailingWhitespace() {
        val f = filter()
        assertEquals(listOf("Hello", " world", ""), f.feed("Hello", " world", "\n"))
        assertEquals("", f.complete("Hello world").text)
    }

    @Test
    fun leadingWhitespaceIsNotShown() {
        val f = filter()
        assertEquals(listOf("", "", "Hi"), f.feed("\n", "  ", "Hi"))
    }

    @Test
    fun leadingThinkBlockStreamsAsThinkingAcrossPieceBoundaries() {
        val f = filter()
        assertEquals(
            listOf("" to "", "reason" to "", "ing" to "", "" to "", "" to "Answer"),
            f.feedBoth("<thi", "nk>reason", "ing</th", "ink>\n\n", "Answer"),
        )
        assertEquals(ReplyFilter.Shown("", ""), f.complete("Answer"))
    }

    @Test
    fun replyOpenedInsideThinkBlockStreamsThinkingThenText() {
        val f = lfm3(startInThink = true)
        assertEquals(
            listOf("" to "", "I'm" to "", "\nthinking" to "", "" to "", "" to "", "" to "Hello"),
            f.feedBoth("\n", "I'm", "\nthinking", "\n</th", "ink>", "Hello"),
        )
        assertEquals(ReplyFilter.Shown("", ""), f.complete("Hello"))
    }

    @Test
    fun thinkingSurroundingWhitespaceIsNotShown() {
        val f = filter()
        assertEquals(listOf("" to "", "a b" to "", "" to "", "" to "Hi"), f.feedBoth("<think> \n", "a b", " \n", "</think>Hi"))
    }

    @Test
    fun toolCallEndsThinkingAndStartsMarkup() {
        val f = lfm3(startInThink = true)
        assertEquals(
            listOf("Need the weather" to "", "" to "", "" to ""),
            f.feedBoth("Need the weather\n<tool_", "call>\n<function=get_weather>\n", "</function>\n</tool_call>"),
        )
        assertEquals(ReplyFilter.Shown("", ""), f.complete(""))
    }

    @Test
    fun replyWithoutThinkBlockHasNoThinking() {
        val f = lfm3(startInThink = false)
        assertEquals(listOf("" to "Hello", "" to " there"), f.feedBoth("Hello", " there"))
        assertEquals(ReplyFilter.Shown("", "!"), f.complete("Hello there!"))
    }

    @Test
    fun unterminatedThinkAtEndReturnsHeldThinkingAndNoText() {
        val f = lfm3(startInThink = true)
        assertEquals(listOf("still" to "", " thinking" to "", "" to ""), f.feedBoth("still", " thinking", "\n"))
        // The parser cannot split an unterminated block; its content must not repeat the thinking as text.
        assertEquals(ReplyFilter.Shown("", ""), f.complete("<think>still thinking"))
    }

    @Test
    fun unterminatedThinkReleasesHeldPartialEndMarker() {
        val f = lfm3(startInThink = true)
        assertEquals(listOf("a" to "", "" to ""), f.feedBoth("a", " </thi"))
        assertEquals(ReplyFilter.Shown(" </thi", ""), f.complete(null))
    }

    @Test
    fun thinkTagLaterInTheTextIsShown() {
        val f = filter()
        assertEquals(listOf("Use", " <think> tags"), f.feed("Use", " <think> tags"))
    }

    @Test
    fun withoutThinkMarkersTheTagIsPlainText() {
        val f = ReplyFilter(null, emptyList(), emptyList())
        assertEquals(listOf("<think>x"), f.feed("<think>x"))
    }

    @Test
    fun markerSplitAcrossPiecesIsHeldBack() {
        val f = filter()
        assertEquals(listOf("Sure", "", ""), f.feed("Sure\n<|tool", "_call", "_start|>[look(x=1)]"))
        assertEquals("", f.complete("Sure").text)
    }

    @Test
    fun heldPrefixThatIsNotAMarkerIsReleased() {
        val f = filter()
        assertEquals(listOf("a", " <|tok"), f.feed("a <|to", "k"))
    }

    @Test
    fun markupAndEverythingAfterItIsSuppressed() {
        val f = filter()
        assertEquals(listOf("Let me check.", "", ""), f.feed("Let me check. $tool[look()]", "<|tool_call_end|>", "More"))
        assertEquals("", f.complete(null).text)
    }

    @Test
    fun completeReturnsParsedContentBeyondWhatWasShown() {
        val f = filter()
        f.feed("The answer is 4", "2 ")
        assertEquals(".", f.complete("The answer is 42.").text)
    }

    @Test
    fun failedParseReleasesHeldTextThatIsNotMarkup() {
        val f = filter()
        assertEquals(listOf("Done"), f.feed("Done <|tool_ca"))
        assertEquals(" <|tool_ca", f.complete(null).text)
    }

    @Test
    fun failedParseInsideThinkBlockShowsNothing() {
        val f = filter()
        assertEquals(listOf("still thinking" to ""), f.feedBoth("<think>still thinking"))
        assertEquals(ReplyFilter.Shown("", ""), f.complete(null))
    }
}
