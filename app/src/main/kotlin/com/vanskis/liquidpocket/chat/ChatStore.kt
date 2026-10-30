package com.vanskis.liquidpocket.chat

import com.vanskis.liquidpocket.engine.GenerationStats
import com.vanskis.liquidpocket.engine.ImageInput
import com.vanskis.liquidpocket.engine.Role
import com.vanskis.liquidpocket.engine.ToolCall

/** A finished turn. [stats] is set on assistant replies when the engine reported them. */
data class Turn(
    val role: Role,
    val text: String,
    val stats: GenerationStats? = null,
    /** USER turns: local files shown or heard with the text. Saved, not yet sent to the engine. */
    val attachments: List<Attachment> = emptyList(),
    /** ASSISTANT turns: the tool calls the model made. */
    val toolCalls: List<ToolCall> = emptyList(),
    /**
     * TOOL turns: the tool whose result [text] is. USER turns: set when the app wrote the turn to
     * hand the model what that tool returned (photos), see [fromTool].
     */
    val toolName: String? = null,
    /** Pixels sent with a [fromTool] turn. Kept in memory only, not saved. */
    val images: List<ImageInput> = emptyList(),
    /** ASSISTANT turns: the model's reasoning before [text]. Shown, never sent back to the engine. */
    val thinking: String? = null,
    /** ASSISTANT turns with [thinking]: how long the model thought, in milliseconds. */
    val thinkingMillis: Long? = null,
    /**
     * A SYSTEM turn the app wrote: a summary of the turns before it (and of any summary before
     * those). From here on the engine sees this summary and the turns after it, see [Compaction].
     */
    val summary: Boolean = false,
    /**
     * USER turns with photos: what they show, in a sentence or two, written by the media model once
     * it answered the turn. A model that cannot see is sent this in their place. Never shown or spoken.
     */
    val caption: String? = null,
) {
    /** A user turn the app added for a tool, not one the user wrote. */
    val fromTool: Boolean get() = role == Role.USER && toolName != null
}

enum class AttachmentKind { IMAGE, AUDIO, DOCUMENT }

/**
 * A file in the app's private storage attached to a message. For a DOCUMENT, [path] is the id the
 * extracted text is saved under ([com.vanskis.liquidpocket.docs.DocumentStore]) and [name] the file
 * name shown on its chip.
 */
data class Attachment(val path: String, val kind: AttachmentKind, val name: String? = null)

data class Conversation(val id: Long, val title: String, val createdAt: Long, val updatedAt: Long)

data class Fact(val id: Long, val text: String, val createdAt: Long)

/**
 * Where [ChatController] keeps conversations and reads the remembered facts. Room backs it in the
 * app ([com.vanskis.liquidpocket.data.ChatRepository]); tests use an in-memory one.
 */
interface ChatStore {
    /** Creates an empty conversation and returns its id. */
    suspend fun createConversation(title: String): Long

    /** The conversation's turns, oldest first. */
    suspend fun turns(conversationId: Long): List<Turn>

    /** Appends [turn] and marks the conversation as just updated. */
    suspend fun appendTurn(conversationId: Long, turn: Turn)

    /** Deletes the conversation's turns from position [fromIndex] (0 is the oldest) on. */
    suspend fun truncate(conversationId: Long, fromIndex: Int)

    /**
     * Replaces the turns from position [fromIndex] on with [turns] (compaction puts a summary in
     * front of the turns it keeps). Stores that can should do it in one transaction.
     */
    suspend fun replaceFrom(conversationId: Long, fromIndex: Int, turns: List<Turn>) {
        truncate(conversationId, fromIndex)
        turns.forEach { appendTurn(conversationId, it) }
    }

    /** Replaces the turn at position [index] (0 is the oldest) with [turn], keeping the turns after it. */
    suspend fun replaceTurn(conversationId: Long, index: Int, turn: Turn) {
        replaceFrom(conversationId, index, listOf(turn) + turns(conversationId).drop(index + 1))
    }

    /** Deletes the conversation and its turns. */
    suspend fun deleteConversation(conversationId: Long)

    /** The remembered facts' texts, oldest first. */
    suspend fun factTexts(): List<String>
}
