package com.vanskis.liquidpocket.chat

import com.vanskis.liquidpocket.engine.ChatMessage
import com.vanskis.liquidpocket.engine.Role
import com.vanskis.liquidpocket.engine.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactionTest {
    private fun user(text: String) = Turn(Role.USER, text)
    private fun bot(text: String) = Turn(Role.ASSISTANT, text)

    @Test fun `compacts from 85 percent of a known context`() {
        assertFalse(Compaction.shouldCompact(6962, 8192))
        assertTrue(Compaction.shouldCompact(6964, 8192))
        assertTrue(Compaction.shouldCompact(85, 100))
        assertFalse(Compaction.shouldCompact(84, 100))
        assertFalse(Compaction.shouldCompact(5000, 0))
    }

    @Test fun `keeps the last four turns and summarizes the ones before`() {
        val turns = listOf(user("q1"), bot("a1"), user("q2"), bot("a2"), user("q3"), bot("a3"))
        val plan = Compaction.plan(turns)!!
        assertNull(plan.previous)
        assertEquals(2, plan.cut)
        assertEquals(listOf("q1", "a1"), plan.summarized.map { it.text })
        assertNull(Compaction.plan(turns.take(4)))
        assertFalse(Compaction.canCompact(turns.take(4)))
    }

    @Test fun `the kept turns start at a message the user wrote, not inside a tool round`() {
        val turns = listOf(
            user("q1"), bot("a1"),
            user("q2"), Turn(Role.ASSISTANT, "", toolCalls = listOf(ToolCall("t", "{}"))),
            Turn(Role.TOOL, "result", toolName = "t"), bot("a2"),
        )
        assertEquals(2, Compaction.plan(turns)!!.cut)
        // Only a tool round after the first message: nothing before it to summarize.
        assertNull(Compaction.plan(listOf(user("q")) + turns.drop(3) + bot("more")))
    }

    @Test fun `a later compaction folds in the newest summary and only the turns after it`() {
        val summary = Turn(Role.SYSTEM, "s1", summary = true)
        val turns = listOf(user("q1"), summary, user("q2"), bot("a2"), user("q3"), bot("a3"), user("q4"), bot("a4"))
        val plan = Compaction.plan(turns)!!
        assertEquals(summary, plan.previous)
        assertEquals(listOf("q2", "a2"), plan.summarized.map { it.text })
        val request = Compaction.request(plan)
        assertEquals(ChatMessage(Role.SYSTEM, Compaction.INSTRUCTIONS), request[0])
        assertTrue(request[1].content.contains("s1"))
        assertFalse(request[1].content.contains("q1"))

        val compacted = Compaction.compacted(turns, plan, "s2")
        assertEquals(listOf("q1", "s1", "q2", "a2", "s2", "q3", "a3", "q4", "a4"), compacted.map { it.text })
        assertTrue(compacted[4].summary)
    }

    @Test fun `the history is the system prompt, the newest summary and the turns after it`() {
        val system = ChatMessage(Role.SYSTEM, "sys")
        val toMessage = { t: Turn -> ChatMessage(t.role, t.text) }
        val plain = listOf(user("q1"), bot("a1"))
        assertEquals(listOf(system) + plain.map(toMessage), Compaction.history(system, plain, toMessage))

        val turns = listOf(
            user("q1"), Turn(Role.SYSTEM, "old", summary = true), user("q2"),
            Turn(Role.SYSTEM, "new", summary = true), user("q3"), bot("a3"),
        )
        assertEquals(
            listOf(system, ChatMessage(Role.SYSTEM, "${Compaction.SUMMARY_HEADER}\nnew"), ChatMessage(Role.USER, "q3"), ChatMessage(Role.ASSISTANT, "a3")),
            Compaction.history(system, turns, toMessage),
        )
    }
}
