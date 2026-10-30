package com.vanskis.liquidpocket.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** A conversation that matches a search; [text] is its first matching message, null when only the title matched. */
data class SearchRow(val conversationId: Long, val title: String, val text: String?)

/** Use [appendMessage] and [deleteConversation]: the single-statement writes they wrap are public only for Room. */
@Dao
abstract class ChatDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC, id DESC")
    abstract fun conversations(): Flow<List<ConversationEntity>>

    @Insert
    abstract suspend fun insertConversation(conversation: ConversationEntity): Long

    @Query("UPDATE conversations SET title = :title WHERE id = :id")
    abstract suspend fun rename(id: Long, title: String)

    @Query("UPDATE conversations SET updatedAt = :updatedAt WHERE id = :id")
    abstract suspend fun touch(id: Long, updatedAt: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    abstract suspend fun deleteConversationRow(id: Long)

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY id")
    abstract suspend fun messages(conversationId: Long): List<MessageEntity>

    @Query("SELECT title FROM conversations WHERE id = :id")
    abstract suspend fun title(id: Long): String?

    /**
     * Conversations whose title or any message matches [pattern] (a LIKE pattern from
     * [com.vanskis.liquidpocket.chat.ChatSearch.likePattern]), newest first. SQLite's LIKE ignores
     * case for ASCII only.
     */
    @Query(
        "SELECT c.id AS conversationId, c.title AS title, " +
            "(SELECT m.text FROM messages m WHERE m.conversationId = c.id AND m.text LIKE :pattern ESCAPE '\\' " +
            "ORDER BY m.id LIMIT 1) AS text " +
            "FROM conversations c WHERE c.title LIKE :pattern ESCAPE '\\' OR EXISTS " +
            "(SELECT 1 FROM messages m WHERE m.conversationId = c.id AND m.text LIKE :pattern ESCAPE '\\') " +
            "ORDER BY c.updatedAt DESC, c.id DESC LIMIT :limit",
    )
    abstract suspend fun search(pattern: String, limit: Int): List<SearchRow>

    @Insert
    abstract suspend fun insertMessage(message: MessageEntity): Long

    /** Deletes the conversation's messages from position [fromIndex] (0 is the oldest) on. */
    @Query(
        "DELETE FROM messages WHERE conversationId = :conversationId AND id >= " +
            "(SELECT id FROM messages WHERE conversationId = :conversationId ORDER BY id LIMIT 1 OFFSET :fromIndex)",
    )
    abstract suspend fun truncate(conversationId: Long, fromIndex: Int)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    abstract suspend fun deleteMessages(conversationId: Long)

    @Transaction
    open suspend fun appendMessage(message: MessageEntity) {
        insertMessage(message)
        touch(message.conversationId, message.createdAt)
    }

    /** Replaces the conversation's messages from position [fromIndex] on with [messages], in one transaction. */
    @Transaction
    open suspend fun replaceMessages(conversationId: Long, fromIndex: Int, messages: List<MessageEntity>) {
        truncate(conversationId, fromIndex)
        messages.forEach { insertMessage(it) }
        messages.lastOrNull()?.let { touch(conversationId, it.createdAt) }
    }

    /** Deletes the messages explicitly too, so it does not depend on foreign keys being enforced. */
    @Transaction
    open suspend fun deleteConversation(id: Long) {
        deleteMessages(id)
        deleteConversationRow(id)
    }

    @Query("SELECT * FROM facts ORDER BY id")
    abstract fun facts(): Flow<List<FactEntity>>

    @Query("SELECT text FROM facts ORDER BY id")
    abstract suspend fun factTexts(): List<String>

    @Insert
    abstract suspend fun insertFact(fact: FactEntity): Long

    @Query("UPDATE facts SET text = :text WHERE id = :id")
    abstract suspend fun updateFact(id: Long, text: String)

    @Query("DELETE FROM facts WHERE id = :id")
    abstract suspend fun deleteFact(id: Long)
}
