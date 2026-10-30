package com.vanskis.liquidpocket.chat

import com.vanskis.liquidpocket.engine.Role

/** Plain-text forms of a saved conversation, for sharing and copying. */
object ChatExport {
    /**
     * `# title`, then a `**You:**` or `**Assistant:**` section per turn, in order. A tool turn is one
     * italic line, attachments are `[photo]` / `[voice message]` / `[document: name]`. System turns and the turns the app
     * wrote to hand the model a tool's photos are left out; the model's thinking is not saved with
     * a turn, so it never appears.
     */
    fun toMarkdown(title: String, turns: List<Turn>): String {
        val sections = mutableListOf("# ${title.trim().ifEmpty { ConversationTitle.UNTITLED }}")
        for (turn in turns) {
            val section = when {
                turn.role == Role.SYSTEM || turn.fromTool -> null
                turn.role == Role.TOOL -> "_Used tool: ${turn.toolName ?: "tool"}_"
                else -> {
                    val label = if (turn.role == Role.USER) "**You:**" else "**Assistant:**"
                    val marks = turn.attachments.joinToString(" ") { it.mark() }
                    val body = listOf(marks, turn.text.trim()).filter { it.isNotEmpty() }.joinToString("\n\n")
                    if (body.isEmpty()) null else "$label\n\n$body"
                }
            }
            if (section != null) sections += section
        }
        return sections.joinToString("\n\n") + "\n"
    }
}

/** How an attachment shows in a transcript: `[photo]`, `[voice message]` or `[document: name]`. */
internal fun Attachment.mark(): String = when (kind) {
    AttachmentKind.IMAGE -> "[photo]"
    AttachmentKind.AUDIO -> "[voice message]"
    AttachmentKind.DOCUMENT -> "[document: ${name ?: "document"}]"
}

/** A conversation found by search; [snippet] is text around the first matching message, null if only the title matched. */
data class ChatSearchHit(val conversationId: Long, val title: String, val snippet: String?)

/** Helpers for the drawer's search over saved chats. */
object ChatSearch {
    /** Milliseconds the query must rest before the database is asked. */
    const val DEBOUNCE_MILLIS = 250L

    /** The LIKE escape character; the DAO declares `ESCAPE '\'`. */
    const val ESCAPE = '\\'

    /** [query] as a `%...%` LIKE pattern in which `%`, `_` and the escape character match themselves. */
    fun likePattern(query: String): String {
        val escaped = buildString {
            for (c in query.trim()) {
                if (c == '%' || c == '_' || c == ESCAPE) append(ESCAPE)
                append(c)
            }
        }
        return "%$escaped%"
    }

    /** About [radius] characters either side of the first case-insensitive match of [query] in [text], on one line. */
    fun snippet(text: String, query: String, radius: Int = 40): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        val q = query.trim()
        val at = if (q.isEmpty()) -1 else flat.indexOf(q, ignoreCase = true)
        if (at < 0) return flat.take(radius * 2).let { if (flat.length > it.length) "$it…" else it }
        val start = maxOf(0, at - radius)
        val end = minOf(flat.length, at + q.length + radius)
        return (if (start > 0) "…" else "") + flat.substring(start, end) + (if (end < flat.length) "…" else "")
    }
}
