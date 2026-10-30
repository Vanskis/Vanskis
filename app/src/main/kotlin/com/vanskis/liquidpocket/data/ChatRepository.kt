package com.vanskis.liquidpocket.data

import com.vanskis.liquidpocket.chat.ChatExport
import com.vanskis.liquidpocket.chat.ChatSearch
import com.vanskis.liquidpocket.chat.ChatSearchHit
import com.vanskis.liquidpocket.chat.ChatStore
import com.vanskis.liquidpocket.chat.Conversation
import com.vanskis.liquidpocket.chat.ConversationTitle
import com.vanskis.liquidpocket.chat.Fact
import com.vanskis.liquidpocket.chat.SystemPrompt
import com.vanskis.liquidpocket.chat.Turn
import com.vanskis.liquidpocket.engine.GenerationStats
import com.vanskis.liquidpocket.engine.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Saved conversations and remembered facts, in Room. */
class ChatRepository(
    private val dao: ChatDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : ChatStore {
    /** Newest first. */
    val conversations: Flow<List<Conversation>> =
        dao.conversations().map { list -> list.map { Conversation(it.id, it.title, it.createdAt, it.updatedAt) } }

    /** Oldest first, the order they go into the system prompt. */
    val facts: Flow<List<Fact>> = dao.facts().map { list -> list.map { Fact(it.id, it.text, it.createdAt) } }

    override suspend fun createConversation(title: String): Long {
        val now = clock()
        return dao.insertConversation(ConversationEntity(title = title, createdAt = now, updatedAt = now))
    }

    override suspend fun turns(conversationId: Long): List<Turn> = dao.messages(conversationId).map { it.toTurn() }

    override suspend fun appendTurn(conversationId: Long, turn: Turn) {
        dao.appendMessage(turn.toEntity(conversationId, clock()))
    }

    override suspend fun truncate(conversationId: Long, fromIndex: Int) = dao.truncate(conversationId, fromIndex)

    override suspend fun replaceFrom(conversationId: Long, fromIndex: Int, turns: List<Turn>) {
        val now = clock()
        dao.replaceMessages(conversationId, fromIndex, turns.map { it.toEntity(conversationId, now) })
    }

    override suspend fun deleteConversation(conversationId: Long) = dao.deleteConversation(conversationId)

    suspend fun rename(conversationId: Long, title: String) {
        dao.rename(conversationId, title.trim().ifEmpty { ConversationTitle.UNTITLED })
    }

    /** Conversations whose title or any message contains [query], ignoring case; empty for a blank query. */
    suspend fun search(query: String, limit: Int = 50): List<ChatSearchHit> {
        if (query.isBlank()) return emptyList()
        return dao.search(ChatSearch.likePattern(query), limit).map {
            ChatSearchHit(it.conversationId, it.title, it.text?.let { text -> ChatSearch.snippet(text, query) })
        }
    }

    /** The conversation as Markdown, or null if it no longer exists. */
    suspend fun export(conversationId: Long): String? {
        val title = dao.title(conversationId) ?: return null
        return ChatExport.toMarkdown(title, turns(conversationId))
    }

    override suspend fun factTexts(): List<String> = dao.factTexts()

    suspend fun addFact(text: String) {
        val clean = text.trim().take(SystemPrompt.MAX_FACT_CHARS)
        if (clean.isNotEmpty()) dao.insertFact(FactEntity(text = clean, createdAt = clock()))
    }

    /** A blank [text] deletes the fact. */
    suspend fun updateFact(id: Long, text: String) {
        val clean = text.trim().take(SystemPrompt.MAX_FACT_CHARS)
        if (clean.isEmpty()) dao.deleteFact(id) else dao.updateFact(id, clean)
    }

    suspend fun deleteFact(id: Long) = dao.deleteFact(id)

    private fun MessageEntity.toTurn(): Turn {
        val stats = if (promptTokens != null && prefillTokensPerSecond != null && generatedTokens != null &&
            decodeTokensPerSecond != null
        ) {
            GenerationStats(promptTokens, prefillTokensPerSecond, generatedTokens, decodeTokensPerSecond)
        } else {
            null
        }
        val extras = MessageJson.decodeExtras(extrasJson)
        return Turn(
            role = Role.valueOf(role),
            text = text,
            stats = stats,
            attachments = MessageJson.decodeAttachments(attachmentsJson),
            toolCalls = MessageJson.decodeToolCalls(toolCallsJson),
            toolName = toolName,
            thinking = extras.thinking,
            thinkingMillis = extras.thinkingMillis,
            thinkingTokens = extras.thinkingTokens,
            summary = extras.summary,
            caption = extras.caption,
            context = extras.context,
        )
    }

    private fun Turn.toEntity(conversationId: Long, now: Long) = MessageEntity(
        conversationId = conversationId,
        role = role.name,
        text = text,
        createdAt = now,
        promptTokens = stats?.promptTokens,
        prefillTokensPerSecond = stats?.prefillTokensPerSecond,
        generatedTokens = stats?.generatedTokens,
        decodeTokensPerSecond = stats?.decodeTokensPerSecond,
        attachmentsJson = MessageJson.encodeAttachments(attachments),
        toolCallsJson = MessageJson.encodeToolCalls(toolCalls),
        toolName = toolName,
        extrasJson = MessageJson.encodeExtras(MessageExtras(thinking, thinkingMillis, summary, caption, thinkingTokens, context)),
    )
}
