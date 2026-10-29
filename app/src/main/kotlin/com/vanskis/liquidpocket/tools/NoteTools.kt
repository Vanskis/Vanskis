package com.vanskis.liquidpocket.tools

import com.vanskis.liquidpocket.chat.SystemPrompt
import com.vanskis.liquidpocket.data.ChatRepository
import com.vanskis.liquidpocket.data.NoteDao
import com.vanskis.liquidpocket.data.NoteEntity
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Saves a note in the app. */
class CreateNoteTool(private val notes: NoteDao) : JsonTool(
    "create_note",
    "Save a note for the user in Liquid Pocket. Needs the user's approval.",
    """
    {"type": "object", "properties": {
      "text": {"type": "string"},
      "title": {"type": "string", "description": "Optional short title"}
    }, "required": ["text"]}
    """,
    needsConfirmation = true,
) {
    override fun check(args: JsonObject) = if (args.string("text") == null) "text is missing" else null

    override fun describe(args: JsonObject): String =
        "Save a note" + (args.string("title")?.let { " “$it”" } ?: "") + ": ${args.string("text")}"

    override suspend fun run(args: JsonObject): ToolResult {
        val text = args.string("text") ?: return ToolResult(ToolResults.error("text is missing"))
        val id = notes.insert(NoteEntity(title = args.string("title").orEmpty(), text = text, createdAt = System.currentTimeMillis()))
        return ToolResults.ok {
            put("status", "saved")
            put("note_id", id)
        }
    }
}

/** Reads back the notes [CreateNoteTool] saved. */
class ListNotesTool(private val notes: NoteDao) : JsonTool(
    "list_notes",
    "List the notes saved in Liquid Pocket, newest first, optionally only those containing some text.",
    """
    {"type": "object", "properties": {
      "query": {"type": "string", "description": "Optional text the note contains"}
    }}
    """,
    needsConfirmation = false,
) {
    override suspend fun run(args: JsonObject): ToolResult {
        val found = notes.find(args.string("query"), limit = 20)
        return ToolResults.ok {
            putJsonArray("notes") {
                found.forEach { note ->
                    addJsonObject {
                        if (note.title.isNotEmpty()) put("title", note.title)
                        put("text", note.text)
                        val created = LocalDateTime.ofInstant(Instant.ofEpochMilli(note.createdAt), ZoneId.systemDefault())
                        put("created", ToolTime.iso(created))
                    }
                }
            }
        }
    }
}

/** Adds a fact to the "Things to remember" list the system prompt carries. */
class RememberTool(private val chats: ChatRepository) : JsonTool(
    "remember",
    "Remember a short fact about the user for future chats. Needs the user's approval.",
    """
    {"type": "object", "properties": {
      "fact": {"type": "string", "description": "One short fact, like: Is vegetarian"}
    }, "required": ["fact"]}
    """,
    needsConfirmation = true,
) {
    override fun check(args: JsonObject) = if (args.string("fact") == null) "fact is missing" else null

    override fun describe(args: JsonObject) = "Remember: “${args.string("fact")?.take(SystemPrompt.MAX_FACT_CHARS)}”"

    override suspend fun run(args: JsonObject): ToolResult {
        val fact = args.string("fact") ?: return ToolResult(ToolResults.error("fact is missing"))
        chats.addFact(fact)
        return ToolResults.ok { put("status", "remembered") }
    }
}
