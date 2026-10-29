package com.vanskis.liquidpocket.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class ReplyFilterTest {
    private val tool = "<|tool_call_start|>"

    private fun filter() = ReplyFilter("<think>", listOf("</think>"), listOf(tool))

    /** Feeds [pieces] one by one and returns what each call showed. */
    private fun ReplyFilter.feed(vararg pieces: String) = pieces.map { append(it) }

    @Test
    fun plainTextStreamsHoldingBackTrailingWhitespace() {
        val f = filter()
        assertEquals(listOf("Hello", " world", ""), f.feed("Hello", " world", "\n"))
        assertEquals("", f.complete("Hello world"))
    }

    @Test
    fun leadingWhitespaceIsNotShown() {
        val f = filter()
        assertEquals(listOf("", "", "Hi"), f.feed("\n", "  ", "Hi"))
    }

    @Test
    fun leadingThinkBlockIsDropped() {
        val f = filter()
        assertEquals(listOf("", "", "", "", "Answer"), f.feed("<thi", "nk>reason", "ing</th", "ink>\n\n", "Answer"))
        assertEquals("", f.complete("Answer"))
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
        assertEquals("", f.complete("Sure"))
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
        assertEquals("", f.complete(null))
    }

    @Test
    fun completeReturnsParsedContentBeyondWhatWasShown() {
        val f = filter()
        f.feed("The answer is 4", "2 ")
        assertEquals(".", f.complete("The answer is 42."))
    }

    @Test
    fun failedParseReleasesHeldTextThatIsNotMarkup() {
        val f = filter()
        assertEquals(listOf("Done"), f.feed("Done <|tool_ca"))
        assertEquals(" <|tool_ca", f.complete(null))
    }

    @Test
    fun failedParseInsideThinkBlockShowsNothing() {
        val f = filter()
        f.feed("<think>still thinking")
        assertEquals("", f.complete(null))
    }
}
