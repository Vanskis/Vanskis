package com.vanskis.liquidpocket.chat

/** Names a conversation after its first message. */
object ConversationTitle {
    const val MAX_LENGTH = 40
    const val UNTITLED = "New chat"

    /** [firstMessage] on one line, cut at a word boundary to at most [MAX_LENGTH] characters plus "…". */
    fun from(firstMessage: String): String {
        val flat = firstMessage.replace(Regex("\\s+"), " ").trim()
        if (flat.isEmpty()) return UNTITLED
        if (flat.length <= MAX_LENGTH) return flat
        var cut = flat.substring(0, MAX_LENGTH)
        if (cut.last().isHighSurrogate()) cut = cut.dropLast(1)
        val space = cut.lastIndexOf(' ')
        // End on a whole word unless that would throw away more than half of the title.
        val endsOnWord = flat[cut.length] == ' '
        if (!endsOnWord && space >= MAX_LENGTH / 2) cut = cut.substring(0, space)
        return cut.trimEnd(' ', ',', '.', ';', ':', '-') + "…"
    }
}
