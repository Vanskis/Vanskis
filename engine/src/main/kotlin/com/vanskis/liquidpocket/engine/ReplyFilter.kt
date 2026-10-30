package com.vanskis.liquidpocket.engine

/**
 * Splits the raw text of a reply as it streams into the thinking and the text to show: a leading
 * thinking block (from [thinkStart] to one of [thinkEnds]) comes out as [Shown.thinking], the rest as
 * [Shown.text] up to the first of [toolStarts], after which the reply is tool-call markup that
 * [complete] hands over to the template's parser. With [startInThink] the prompt already opened the
 * block (its generation prompt ends with [thinkStart]), so the reply starts inside it.
 *
 * A marker can arrive split over several tokens, so any suffix that could begin one is held back,
 * together with the whitespace before it (the parser trims content, and text before a tool call
 * usually ends in a newline). Leading and trailing whitespace of the thinking and of the text is never
 * shown. A think end that is also a tool start (`<tool_call>` for LFM3) ends the thinking and starts the markup.
 *
 * The markers come from [LlamaNative.nativeReplyMarkers]; empty lists disable that stage.
 */
internal class ReplyFilter(
    private val thinkStart: String?,
    private val thinkEnds: List<String>,
    private val toolStarts: List<String>,
    startInThink: Boolean = false,
) {
    /** What one call made showable: [thinking] comes before [text] in the reply. Either may be empty. */
    data class Shown(val thinking: String, val text: String)

    private enum class State {
        /** Nothing shown yet: skipping whitespace, waiting to see whether a think block opens. */
        START,
        /** Inside the leading think block. */
        THINK,
        /** Showing text. */
        TEXT,
        /** In tool-call markup: nothing more is shown. */
        MARKUP,
    }

    private var state = if (startInThink) State.THINK else State.START
    private var thinkDone = startInThink || thinkStart.isNullOrEmpty()

    /** In the think block and nothing of it shown yet: its leading whitespace is skipped. */
    private var thinkLead = startInThink

    /** True while the reply is inside the think block. */
    val thinking: Boolean get() = state == State.THINK

    /** Received text not yet shown or dropped. */
    private val pending = StringBuilder()

    /** All text returned by [append] so far. */
    private val shown = StringBuilder()

    /** Feeds the next piece of the reply; returns the thinking and text that can be shown now. */
    fun append(text: String): Shown {
        if (state == State.MARKUP) return NOTHING
        pending.append(text)
        val think = StringBuilder()
        val out = StringBuilder()
        while (true) {
            when (state) {
                State.START -> {
                    val lead = pending.indexOfFirst { !it.isWhitespace() }
                    pending.deleteRange(0, if (lead < 0) pending.length else lead)
                    if (pending.isEmpty()) break
                    if (!thinkDone) {
                        val start = thinkStart!!
                        if (pending.startsWith(start)) {
                            pending.deleteRange(0, start.length)
                            thinkDone = true
                            thinkLead = true
                            state = State.THINK
                            continue
                        }
                        if (start.startsWith(pending)) break // could still become the think tag
                        thinkDone = true
                    }
                    state = State.TEXT
                }
                State.THINK -> {
                    if (thinkLead) {
                        val lead = pending.indexOfFirst { !it.isWhitespace() }
                        pending.deleteRange(0, if (lead < 0) pending.length else lead)
                        if (pending.isEmpty()) break
                        thinkLead = false
                    }
                    val (at, end) = earliest(thinkEnds)
                    if (end != null) {
                        think.append(pending, 0, trimTrailingWhitespace(at))
                        // A tool start that ends the thinking stays: the text state turns it into markup.
                        pending.deleteRange(0, if (end in toolStarts) at else at + end.length)
                        state = State.START
                        continue
                    }
                    val cut = trimTrailingWhitespace(pending.length - partialMarkerLength(thinkEnds))
                    think.append(pending, 0, cut)
                    pending.deleteRange(0, cut)
                    break
                }
                State.TEXT -> {
                    val (at, start) = earliest(toolStarts)
                    if (start != null) {
                        val cut = trimTrailingWhitespace(at)
                        out.append(pending, 0, cut)
                        pending.setLength(at) // keep the whitespace before the markup, drop the markup
                        pending.deleteRange(0, cut)
                        state = State.MARKUP
                        break
                    }
                    val cut = holdBack()
                    out.append(pending, 0, cut)
                    pending.deleteRange(0, cut)
                    break
                }
                State.MARKUP -> break
            }
        }
        shown.append(out)
        return if (think.isEmpty() && out.isEmpty()) NOTHING else Shown(think.toString(), out.toString())
    }

    /**
     * Returns the rest of the reply to show once it has ended. [parsedContent] is the content the
     * template's parser extracted, or null if parsing failed: then the held-back text that is not
     * markup is returned instead. A reply that ended inside its think block has no text; the
     * held-back rest of its thinking is returned.
     */
    fun complete(parsedContent: String?): Shown {
        if (state == State.THINK) return Shown(pending.toString().trimEnd(), "")
        if (parsedContent != null && parsedContent.startsWith(shown)) return Shown("", parsedContent.substring(shown.length))
        return when (state) {
            State.START, State.TEXT -> Shown("", pending.toString().trimEnd())
            State.THINK, State.MARKUP -> NOTHING
        }
    }

    /** How much of [pending] can be shown: all but a suffix that may begin a tool marker and the whitespace before it. */
    private fun holdBack(): Int = trimTrailingWhitespace(pending.length - partialMarkerLength(toolStarts))

    private fun trimTrailingWhitespace(end: Int): Int {
        var cut = end
        while (cut > 0 && pending[cut - 1].isWhitespace()) cut--
        return cut
    }

    /** Length of the longest suffix of [pending] that is a proper prefix of one of [markers]. */
    private fun partialMarkerLength(markers: List<String>): Int {
        var best = 0
        for (m in markers) {
            for (k in minOf(m.length - 1, pending.length) downTo best + 1) {
                if (pending.regionMatches(pending.length - k, m, 0, k)) {
                    best = k
                    break
                }
            }
        }
        return best
    }

    /** The first occurrence in [pending] of any of [markers]: its index and the marker, or (-1, null). */
    private fun earliest(markers: List<String>): Pair<Int, String?> {
        var at = -1
        var found: String? = null
        for (m in markers) {
            if (m.isEmpty()) continue
            val i = pending.indexOf(m)
            if (i >= 0 && (at < 0 || i < at)) {
                at = i
                found = m
            }
        }
        return at to found
    }

    private companion object {
        val NOTHING = Shown("", "")
    }
}
