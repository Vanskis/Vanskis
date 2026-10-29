package com.vanskis.liquidpocket.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "conversations", indices = [Index("updatedAt")])
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** Epoch milliseconds. */
    val createdAt: Long,
    /** Epoch milliseconds of the newest message; the drawer lists newest first. */
    val updatedAt: Long,
)

/** One turn. Turns of a conversation are ordered by [id]. */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    /** A [com.vanskis.liquidpocket.engine.Role] name: SYSTEM, USER, ASSISTANT or TOOL. */
    val role: String,
    val text: String,
    val createdAt: Long,
    // GenerationStats of an assistant reply, all null when the engine reported none.
    val promptTokens: Int? = null,
    val prefillTokensPerSecond: Double? = null,
    val generatedTokens: Int? = null,
    val decodeTokensPerSecond: Double? = null,
    /** Local files with their kind, see [MessageJson.encodeAttachments]. */
    val attachmentsJson: String? = null,
    /** See [MessageJson.encodeToolCalls]. */
    val toolCallsJson: String? = null,
    /** TOOL turns: the tool whose result [text] is. */
    val toolName: String? = null,
)

@Entity(tableName = "facts")
data class FactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val createdAt: Long,
)
