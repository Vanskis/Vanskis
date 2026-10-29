package com.vanskis.liquidpocket.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

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
