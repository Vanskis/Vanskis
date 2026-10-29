package com.vanskis.liquidpocket.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ConversationTrimmerTest {
    private fun sys(t: String) = ChatMessage(Role.SYSTEM, t)
    private fun user(t: String) = ChatMessage(Role.USER, t)
    private fun bot(t: String) = ChatMessage(Role.ASSISTANT, t)

    /** One token per character plus 10 per message: a stand-in for the native token count. */
    private val count: (List<ChatMessage>) -> Int = { msgs -> msgs.sumOf { it.content.length + 10 } }

    @Test
    fun budgetReservesReplyCappedAtHalfTheContext() {
        assertEquals(ContextBudget(7168, 5120), ContextBudget.of(8192, 1024))
        assertEquals(ContextBudget(1024, 512), ContextBudget.of(2048, 5000))
        assertEquals(ContextBudget(8191, 6143), ContextBudget.of(8192, 0))
    }

    @Test
    fun fittingConversationIsReturnedUnchanged() {
        val msgs = listOf(sys("s"), user("hello"), bot("hi"), user("again"))
        val result = ConversationTrimmer.trim(msgs, ContextBudget(100, 50), count)
        assertSame(msgs, result.messages)
        assertEquals(count(msgs), result.promptTokens)
    }

    @Test
    fun overflowDropsOldestTurnsDownToTargetKeepingSystem() {
        val msgs = listOf(
            sys("s"), // 11
            user("a".repeat(30)), bot("b".repeat(30)), // 40 + 40
            user("c".repeat(30)), bot("d".repeat(30)), // 40 + 40
            user("e"), // 11
        )
        // Total 182 > max 150; target 100 needs both old turns gone (182 -> 102 -> 22).
        val result = ConversationTrimmer.trim(msgs, ContextBudget(150, 100), count)
        assertEquals(listOf(sys("s"), user("e")), result.messages)
        assertEquals(22, result.promptTokens)
    }

    @Test
    fun stopsDroppingOnceTargetIsReached() {
        val msgs = listOf(user("a".repeat(50)), bot("b"), user("c".repeat(20)), bot("d"), user("e"))
        // 60 + 11 + 30 + 11 + 11 = 123 > 120; dropping the first turn gives 52 <= target 60.
        val result = ConversationTrimmer.trim(msgs, ContextBudget(120, 60), count)
        assertEquals(listOf(user("c".repeat(20)), bot("d"), user("e")), result.messages)
    }

    @Test
    fun historyNeverStartsWithAnAssistantReply() {
        val msgs = listOf(bot("greeting".repeat(5)), user("a".repeat(40)), bot("b".repeat(40)), user("q"))
        val result = ConversationTrimmer.trim(msgs, ContextBudget(100, 80), count)
        assertEquals(Role.USER, result.messages.first().role)
        assertEquals(user("q"), result.messages.last())
        assertTrue(result.promptTokens <= 80)
    }

    private fun calls(t: String) = ChatMessage(Role.ASSISTANT, t, toolCalls = listOf(ToolCall("f", "{}")))
    private fun result(t: String) = ChatMessage(Role.TOOL, t, toolName = "f")

    @Test
    fun currentTurnFromLastUserMessageIsProtected() {
        val msgs = listOf(
            sys("s"), // 11
            user("a".repeat(30)), calls("b"), result("c".repeat(30)), bot("d"), // 40 + 11 + 40 + 11
            user("q"), calls("e".repeat(40)), result("r".repeat(40)), // 11 + 50 + 50
        )
        // Total 224 > 200; the old turn goes (with its tool calls and results), the current one stays.
        val result = ConversationTrimmer.trim(msgs, ContextBudget(200, 10), count)
        assertEquals(listOf(sys("s"), user("q"), calls("e".repeat(40)), result("r".repeat(40))), result.messages)
        assertEquals(122, result.promptTokens)
    }

    @Test
    fun historyNeverStartsWithAToolResult() {
        val msgs = listOf(user("a".repeat(40)), calls("x"), result("y".repeat(10)), user("q"))
        // 50 + 11 + 20 + 11 = 92 > 90; dropping the first user turn takes its tool result with it.
        val result = ConversationTrimmer.trim(msgs, ContextBudget(90, 45), count)
        assertEquals(listOf(user("q")), result.messages)
    }

    @Test
    fun tooLongLastMessageThrows() {
        val msgs = listOf(sys("s"), user("old"), bot("reply"), user("x".repeat(200)))
        try {
            ConversationTrimmer.trim(msgs, ContextBudget(100, 50), count)
            fail("expected EngineException")
        } catch (e: EngineException) {
            assertTrue(e.message!!.contains("too long"))
        }
    }
}
